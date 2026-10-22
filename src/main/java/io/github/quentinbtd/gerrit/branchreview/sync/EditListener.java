// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.sync;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import io.github.quentinbtd.gerrit.branchreview.config.ProjectConfigLoader;
import io.github.quentinbtd.gerrit.branchreview.config.ServerSettings;
import io.github.quentinbtd.gerrit.branchreview.state.ChangeLink;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import java.io.IOException;
import org.eclipse.jgit.lib.Repository;

/**
 * Reacts to patch sets uploaded in Gerrit on a branch-review Change (inline edits, uploads to
 * {@code refs/for}) by scheduling a synchronization as the uploader. Patch sets created by the
 * plugin itself are ignored. The synchronization then carries content edits back to the source
 * branch when {@code syncEditsToBranch} is enabled; message-only edits and Gerrit rebases are
 * recognized and change nothing.
 */
@Singleton
public class EditListener implements RevisionCreatedListener {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  private final GitRepositoryManager repoManager;
  private final StateStore store;
  private final ProjectConfigLoader configLoader;
  private final ServerSettings settings;
  private final SyncExecutor executor;

  @Inject
  EditListener(
      GitRepositoryManager repoManager,
      StateStore store,
      ProjectConfigLoader configLoader,
      ServerSettings settings,
      SyncExecutor executor) {
    this.repoManager = repoManager;
    this.store = store;
    this.configLoader = configLoader;
    this.settings = settings;
    this.executor = executor;
  }

  @Override
  public void onRevisionCreated(Event event) {
    if (settings.emergencyDisabled()
        || event.getWho() == null
        || event.getWho()._accountId == null
        || event.getRevision() == null) {
      return;
    }
    Project.NameKey project = Project.nameKey(event.getChange().project);
    BranchReviewConfig cfg = configLoader.load(project);
    if (!cfg.isEnabled() || !cfg.syncEditsToBranch()) {
      return;
    }
    int number = event.getChange()._number;
    String revision = event.getRevision().commit != null ? event.getRevision().commit.commit : null;
    try (Repository repo = repoManager.openRepository(project)) {
      StateStore.Loaded<ChangeLink> link = store.loadLink(repo, number);
      if (!link.exists()) {
        return;
      }
      Lifecycle lc = store.loadLifecycle(repo, link.value().sourceRef).value();
      if (lc == null || !lc.isActive() || !link.value().lifecycleId.equals(lc.id)) {
        return;
      }
      if (revision != null
          && (revision.equals(lc.lastSyntheticCommit)
              || (lc.pending != null && revision.equals(lc.pending.commit)))) {
        return; // Our own patch set.
      }
      if (lc.pending != null && lc.pending.patchSet == event.getRevision()._number) {
        return; // Our own patch set, reservation not yet completed.
      }
      executor.submit(
          project,
          lc.sourceRef,
          Account.id(event.getWho()._accountId),
          SyncService.Trigger.EDIT,
          SyncService.Options.NONE);
    } catch (IOException | RuntimeException e) {
      log.atWarning().withCause(e).log(
          "cannot handle new patch set of %s change %d", project, number);
    }
  }
}
