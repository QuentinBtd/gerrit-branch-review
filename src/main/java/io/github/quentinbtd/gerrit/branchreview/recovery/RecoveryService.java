// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.recovery;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.git.GitRepositoryManager;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
      Metrics metrics) {
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

  /** One recovery pass over all enabled projects. Returns the number of lifecycles needing work. */
  public int runOnce() {
    int backlog = 0;
    Instant now = Instant.now();
    for (Project.NameKey project : projectCache.all()) {
      if (!configLoader.load(project).isEnabled()) {
        continue;
      }
      try (Repository repo = repoManager.openRepository(project)) {
        for (Lifecycle lc : store.listLifecycles(repo)) {
          if (needsSync(lc)) {
            backlog++;
            if (lc.attempts < settings.maxAttempts()
                && (lc.nextAttemptAfter == null || Instant.parse(lc.nextAttemptAfter).isBefore(now))
                && lc.lastActor > 0) {
              executor.submit(
                  project,
                  lc.sourceRef,
                  Account.id(lc.lastActor),
                  SyncService.Trigger.RECOVERY,
                  SyncService.Options.NONE,
                  SyncExecutor.Priority.BACKGROUND);
            }
          } else if (lc.status == Lifecycle.Status.MERGED
              && lc.merge != null
              && ("pending".equals(lc.merge.cleanup)
                  || ("failed".equals(lc.merge.cleanup)
                      && lc.merge.cleanupAttempts < settings.maxAttempts()))) {
            backlog++;
            int change = lc.changeNumber;
            executor.execute(
                () -> mergeHandler.handleMerged(project, change, 0, null),
                SyncExecutor.Priority.BACKGROUND);
          } else if (lc.status == Lifecycle.Status.ACTIVE
              && lc.merge == null
              && lc.changeNumber > 0
              && lc.syncState == Lifecycle.SyncState.SYNCED) {
            // A merge event may have been missed (e.g. restart during submit): reconcile.
            int change = lc.changeNumber;
            executor.execute(
                () -> mergeHandler.handleMerged(project, change, 0, null),
                SyncExecutor.Priority.BACKGROUND);
          }
        }
      } catch (IOException | RuntimeException e) {
        log.atWarning().withCause(e).log("recovery scan of %s failed", project);
      }
    }
    metrics.setRetryBacklog(backlog);
    return backlog;
  }

  private static boolean needsSync(Lifecycle lc) {
    return lc.isActive()
        && (lc.pending != null
            || lc.syncState == Lifecycle.SyncState.FAILED
            || lc.syncState == Lifecycle.SyncState.PENDING);
  }
}
