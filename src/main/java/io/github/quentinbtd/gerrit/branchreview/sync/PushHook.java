// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.sync;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.ReceivePackInitializer;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import io.github.quentinbtd.gerrit.branchreview.config.ProjectConfigLoader;
import io.github.quentinbtd.gerrit.branchreview.config.ServerSettings;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * Receive integration.
 *
 * <p>Gerrit applies all ref updates of a push inside its own pre-receive processing. JGit then
 * sends the per-ref status report and afterwards calls the post-receive hook chain while the
 * side-band channel is still open, which is why messages written here reach the client as {@code
 * remote:} lines before the push completes (verified for SSH and smart HTTP, see
 * docs/api-investigation.md). This hook only sees commands whose result is {@code OK}, i.e. the
 * source update is already committed; it never changes command results.
 */
@Singleton
public class PushHook implements PostReceiveHook, ReceivePackInitializer {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();
  static final String PREFIX = "branch-review: ";

  private final Map<ReceivePack, Project.NameKey> projects =
      Collections.synchronizedMap(new WeakHashMap<>());
  private final Provider<CurrentUser> currentUser;
  private final ProjectConfigLoader configLoader;
  private final ServerSettings settings;
  private final SyncExecutor executor;
  private final GitRepositoryManager repoManager;

  @Inject
  PushHook(
      Provider<CurrentUser> currentUser,
      ProjectConfigLoader configLoader,
      ServerSettings settings,
      SyncExecutor executor,
      GitRepositoryManager repoManager) {
    this.repoManager = repoManager;
    this.currentUser = currentUser;
    this.configLoader = configLoader;
    this.settings = settings;
    this.executor = executor;
  }

  @Override
  public void init(Project.NameKey project, ReceivePack rp) {
    projects.put(rp, project);
  }

  @Override
  public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
    Project.NameKey project = projects.remove(rp);
    if (project == null || settings.emergencyDisabled()) {
      return;
    }
    try {
      handle(rp, project, commands);
    } catch (RuntimeException e) {
      log.atSevere().withCause(e).log("branch-review post-receive failed for %s", project);
      rp.sendMessage(
          PREFIX + "internal error; branch updates were accepted but may not be synchronized");
    }
  }

  private void handle(
      ReceivePack rp, Project.NameKey project, Collection<ReceiveCommand> commands) {
    List<ReceiveCommand> heads = new ArrayList<>();
    boolean configUpdated = false;
    for (ReceiveCommand c : commands) {
      if (c.getResult() != ReceiveCommand.Result.OK) {
        continue;
      }
      if (RefNames.REFS_CONFIG.equals(c.getRefName())) {
        configUpdated = true;
      } else if (BranchReviewConfig.isOrdinaryBranch(c.getRefName())) {
        heads.add(c);
      }
    }
    if (heads.isEmpty()) {
      return;
    }
    BranchReviewConfig cfg = configLoader.load(project);
    if (cfg.status() == BranchReviewConfig.Status.DISABLED) {
      return;
    }
    if (cfg.status() == BranchReviewConfig.Status.INVALID) {
      rp.sendMessage(
          PREFIX
              + "branch-review.config is invalid; branch updates were accepted but NOT"
              + " synchronized: "
              + String.join("; ", cfg.errors()));
      return;
    }
    CurrentUser user = currentUser.get();
    if (!user.isIdentifiedUser()) {
      return;
    }
    if (configUpdated) {
      for (ReceiveCommand c : heads) {
        rp.sendMessage(
            PREFIX
                + c.getRefName()
                + ": refs/meta/config changed in the same push; not synchronized. Run: ssh <host>"
                + " branch-review sync "
                + project.get()
                + " "
                + c.getRefName());
      }
      return;
    }

    Map<String, Optional<CompletableFuture<SyncResult>>> futures = new LinkedHashMap<>();
    for (ReceiveCommand c : heads) {
      if (cfg.isEligibleSource(c.getRefName()) || hasLifecycle(project, c.getRefName())) {
        futures.put(
            c.getRefName(),
            executor.submit(
                project,
                c.getRefName(),
                user.getAccountId(),
                SyncService.Trigger.PUSH,
                SyncService.Options.NONE));
      }
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.pushWaitSeconds());
    for (Map.Entry<String, Optional<CompletableFuture<SyncResult>>> e : futures.entrySet()) {
      SyncResult r;
      if (e.getValue().isEmpty()) {
        r =
            SyncResult.of(
                SyncResult.Kind.FAILED,
                e.getKey(),
                "server busy; synchronization was not started. Push again or run branch-review"
                    + " sync");
      } else {
        r = await(e.getKey(), e.getValue().get(), deadline);
      }
      for (String line : r.render()) {
        rp.sendMessage(PREFIX + line);
      }
    }
  }

  /** Whether a (possibly no longer eligible) branch has plugin state that should be reported. */
  private boolean hasLifecycle(Project.NameKey project, String ref) {
    try (Repository repo = repoManager.openRepository(project)) {
      return repo.exactRef(StateStore.lifecycleRef(ref)) != null;
    } catch (IOException e) {
      return false;
    }
  }

  private static SyncResult await(String ref, CompletableFuture<SyncResult> f, long deadline) {
    try {
      return f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      return SyncResult.of(
          SyncResult.Kind.IN_PROGRESS,
          ref,
          "check the result with: ssh <host> branch-review status");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return SyncResult.of(SyncResult.Kind.IN_PROGRESS, ref);
    } catch (ExecutionException e) {
      return SyncResult.of(SyncResult.Kind.FAILED, ref, "internal error: " + e.getCause());
    }
  }
}
