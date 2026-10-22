// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.sync;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.config.ServerSettings;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded worker pool for synchronizations. Work runs in a request context of the acting account so
 * that Gerrit permission checks and auditing see the real user. The pool is owned by the plugin and
 * shut down on unload.
 */
@Singleton
public class SyncExecutor implements LifecycleListener {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  private final WorkQueue workQueue;
  private final ServerSettings settings;
  private final SyncService syncService;
  private final OneOffRequestContext requestContext;
  private final AtomicInteger inFlight = new AtomicInteger();
  private volatile ScheduledExecutorService pool;

  @Inject
  SyncExecutor(
      WorkQueue workQueue,
      ServerSettings settings,
      SyncService syncService,
      OneOffRequestContext requestContext) {
    this.workQueue = workQueue;
    this.settings = settings;
    this.syncService = syncService;
    this.requestContext = requestContext;
  }

  @Override
  public void start() {
    pool = workQueue.createQueue(settings.threads(), "BranchReview");
  }

  @Override
  public void stop() {
    ScheduledExecutorService p = pool;
    pool = null;
    if (p != null) {
      p.shutdown();
      try {
        if (!p.awaitTermination(30, TimeUnit.SECONDS)) {
          // Interrupted work leaves a durable reservation that recovery resolves on restart.
          p.shutdownNow();
        }
      } catch (InterruptedException e) {
        p.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Admission class. Every task counts against {@code maxQueued}; background work (recovery) is
   * only admitted while at most half of it is used, so that a large recovery pass can never fill
   * the queue and turn pushes away.
   */
  public enum Priority {
    FOREGROUND,
    BACKGROUND
  }

  private boolean admit(Priority priority) {
    int limit =
        priority == Priority.FOREGROUND
            ? settings.maxQueued()
            : Math.max(1, settings.maxQueued() / 2);
    while (true) {
      int n = inFlight.get();
      if (n >= limit) {
        return false;
      }
      if (inFlight.compareAndSet(n, n + 1)) {
        return true;
      }
    }
  }

  /** Submits a sync; empty when the queue is full or the plugin is stopping. */
  public Optional<CompletableFuture<SyncResult>> submit(
      Project.NameKey project,
      String ref,
      Account.Id actor,
      SyncService.Trigger trigger,
      SyncService.Options opts) {
    return submit(project, ref, actor, trigger, opts, Priority.FOREGROUND);
  }

  public Optional<CompletableFuture<SyncResult>> submit(
      Project.NameKey project,
      String ref,
      Account.Id actor,
      SyncService.Trigger trigger,
      SyncService.Options opts,
      Priority priority) {
    ScheduledExecutorService p = pool;
    if (p == null || !admit(priority)) {
      return Optional.empty();
    }
    CompletableFuture<SyncResult> f = new CompletableFuture<>();
    try {
      p.execute(
          () -> {
            try (ManualRequestContext ctx = requestContext.openAs(actor)) {
              f.complete(
                  syncService.sync(project, ref, ctx.getUser().asIdentifiedUser(), trigger, opts));
            } catch (Throwable t) {
              log.atSevere().withCause(t).log("sync task failed for %s %s", project, ref);
              f.completeExceptionally(t);
            } finally {
              inFlight.decrementAndGet();
            }
          });
    } catch (RuntimeException e) {
      inFlight.decrementAndGet();
      return Optional.empty();
    }
    return Optional.of(f);
  }

  /** Runs a task (e.g. cleanup) on the plugin pool. Returns false if it was not accepted. */
  public boolean execute(Runnable r) {
    return execute(r, Priority.FOREGROUND);
  }

  /** As {@link #execute(Runnable)}, subject to the admission limit of {@code priority}. */
  public boolean execute(Runnable r, Priority priority) {
    ScheduledExecutorService p = pool;
    if (p == null || !admit(priority)) {
      return false;
    }
    try {
      p.execute(
          () -> {
            try {
              r.run();
            } catch (RuntimeException e) {
              log.atSevere().withCause(e).log("branch-review task failed");
            } finally {
              inFlight.decrementAndGet();
            }
          });
      return true;
    } catch (RuntimeException e) {
      inFlight.decrementAndGet();
      return false;
    }
  }

  public ScheduledExecutorService pool() {
    return pool;
  }
}
