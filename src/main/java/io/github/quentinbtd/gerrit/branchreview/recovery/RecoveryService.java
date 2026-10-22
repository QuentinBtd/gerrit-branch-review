// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.recovery;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.Metrics;
import io.github.quentinbtd.gerrit.branchreview.config.ProjectConfigLoader;
import io.github.quentinbtd.gerrit.branchreview.config.ServerSettings;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import io.github.quentinbtd.gerrit.branchreview.submit.MergeHandler;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncExecutor;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncService;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.Repository;

/**
 * Periodically retries work that was accepted but not completed: unresolved reservations, failed
 * synchronizations and pending cleanups. Runs once shortly after start (crash/restart recovery) and
 * then every {@code recoveryIntervalSeconds}. Retries act as the last pushing user, so a retry can
 * never do more than that user's original push was allowed to do. Recovery work is admitted as
 * background work (see {@link SyncExecutor.Priority}); what does not fit waits for the next pass.
 */
@Singleton
public class RecoveryService implements LifecycleListener {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  private final ProjectCache projectCache;
  private final ProjectConfigLoader configLoader;
  private final GitRepositoryManager repoManager;
  private final StateStore store;
  private final ServerSettings settings;
  private final SyncExecutor executor;
  private final MergeHandler mergeHandler;
  private final Metrics metrics;
  private final ChangeNotes.Factory notesFactory;
  private ScheduledFuture<?> task;

  @Inject
  RecoveryService(
      ProjectCache projectCache,
      ProjectConfigLoader configLoader,
      GitRepositoryManager repoManager,
      StateStore store,
      ServerSettings settings,
      SyncExecutor executor,
      MergeHandler mergeHandler,
      Metrics metrics,
      ChangeNotes.Factory notesFactory) {
    this.notesFactory = notesFactory;
    this.projectCache = projectCache;
    this.configLoader = configLoader;
    this.repoManager = repoManager;
    this.store = store;
    this.settings = settings;
    this.executor = executor;
    this.mergeHandler = mergeHandler;
    this.metrics = metrics;
  }

  @Override
  public void start() {
    ScheduledExecutorService pool = executor.pool();
    if (pool == null || settings.emergencyDisabled()) {
      return;
    }
    int interval = settings.recoveryIntervalSeconds();
    if (interval > 0) {
      task = pool.scheduleWithFixedDelay(this::runSafely, 30, interval, TimeUnit.SECONDS);
    } else {
      task = pool.schedule(this::runSafely, 30, TimeUnit.SECONDS);
    }
  }

  @Override
  public void stop() {
    if (task != null) {
      task.cancel(false);
    }
  }

  private void runSafely() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      log.atWarning().withCause(e).log("branch-review recovery pass failed");
    }
  }

  /** A unit of recovery work; {@code admit} returns whether the executor accepted it. */
  private record Work(String key, BooleanSupplier admit) {}

  /** Key of the last admitted work item; the next pass resumes after it (fair rotation). */
  private String resumeAfter;

  /**
   * One recovery pass over all enabled projects. Returns the number of lifecycles needing work.
   *
   * <p>Healthy lifecycles cost no queue capacity: whether a Change was merged without the plugin
   * noticing is read here, and only real work is submitted. Work is admitted as background work in
   * a stable order starting after the last admitted item of the previous pass, so that items late
   * in a large scan are reached even when capacity is short.
   */
  public synchronized int runOnce() {
    Instant now = Instant.now();
    int backlog = 0;
    List<Work> work = new ArrayList<>();
    for (Project.NameKey project : projectCache.all()) {
      if (!configLoader.load(project).isEnabled()) {
        continue;
      }
      try (Repository repo = repoManager.openRepository(project)) {
        for (Lifecycle lc : store.listLifecycles(repo)) {
          String key = project.get() + '\0' + lc.sourceRef;
          int change = lc.changeNumber;
          if (lc.status == Lifecycle.Status.MERGED) {
            if (lc.merge != null
                && ("pending".equals(lc.merge.cleanup)
                    || ("failed".equals(lc.merge.cleanup)
                        && lc.merge.cleanupAttempts < settings.maxAttempts()))) {
              backlog++;
              work.add(new Work(key, () -> mergeWork(project, change)));
            }
            continue;
          }
          if (!lc.isActive()) {
            continue;
          }
          if (change > 0 && lc.pending == null && isMerged(project, change)) {
            // A merge event was missed (queue full, restart during submit): reconcile.
            backlog++;
            work.add(new Work(key, () -> mergeWork(project, change)));
          } else if (needsSync(lc)) {
            backlog++;
            if (lc.attempts < settings.maxAttempts()
                && (lc.nextAttemptAfter == null || Instant.parse(lc.nextAttemptAfter).isBefore(now))
                && lc.lastActor > 0) {
              String ref = lc.sourceRef;
              Account.Id actor = Account.id(lc.lastActor);
              work.add(
                  new Work(
                      key,
                      () ->
                          executor
                              .submit(
                                  project,
                                  ref,
                                  actor,
                                  SyncService.Trigger.RECOVERY,
                                  SyncService.Options.NONE,
                                  SyncExecutor.Priority.BACKGROUND)
                              .isPresent()));
            }
          }
        }
      } catch (IOException | RuntimeException e) {
        log.atWarning().withCause(e).log("recovery scan of %s failed", project);
      }
    }
    work.sort(Comparator.comparing(Work::key));
    Map<String, BooleanSupplier> byKey = new LinkedHashMap<>();
    work.forEach(w -> byKey.put(w.key(), w.admit()));
    resumeAfter =
        admitInRotation(
            new ArrayList<>(byKey.keySet()), resumeAfter, k -> byKey.get(k).getAsBoolean());
    metrics.setRetryBacklog(backlog);
    return backlog;
  }

  /**
   * Offers {@code sortedKeys} to {@code admit} starting after {@code resumeAfter} and wrapping
   * around, stopping at the first refusal. Returns the position for the next pass: the last
   * admitted key if capacity ran out, {@code resumeAfter} if nothing was admitted, or {@code null}
   * if everything was admitted.
   */
  public static String admitInRotation(
      List<String> sortedKeys, String resumeAfter, Predicate<String> admit) {
    int start = 0;
    if (resumeAfter != null) {
      while (start < sortedKeys.size() && sortedKeys.get(start).compareTo(resumeAfter) <= 0) {
        start++;
      }
    }
    String lastAdmitted = null;
    for (int n = 0; n < sortedKeys.size(); n++) {
      String key = sortedKeys.get((start + n) % sortedKeys.size());
      if (!admit.test(key)) {
        return lastAdmitted != null ? lastAdmitted : resumeAfter;
      }
      lastAdmitted = key;
    }
    return null;
  }

  private boolean mergeWork(Project.NameKey project, int change) {
    return executor.execute(
        () -> mergeHandler.handleMerged(project, change, 0, null),
        SyncExecutor.Priority.BACKGROUND);
  }

  private boolean isMerged(Project.NameKey project, int change) {
    try {
      return notesFactory.createChecked(project, Change.id(change)).getChange().isMerged();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static boolean needsSync(Lifecycle lc) {
    return lc.isActive()
        && (lc.pending != null
            || lc.syncState == Lifecycle.SyncState.FAILED
            || lc.syncState == Lifecycle.SyncState.PENDING);
  }
}
