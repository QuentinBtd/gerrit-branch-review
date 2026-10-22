// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.ExtensionRegistry.Registration;
import com.google.gerrit.entities.Account;
import com.google.gerrit.server.Sequences;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import io.github.quentinbtd.gerrit.branchreview.api.PostSync;
import io.github.quentinbtd.gerrit.branchreview.recovery.RecoveryService;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncExecutor;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncResult;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.junit.Test;

public class RecoveryIT extends AbstractBranchReviewIT {
  private static final String FEATURE = "refs/heads/feature/recover";

  @Inject private Sequences sequences;
  @Inject private com.google.gerrit.acceptance.ExtensionRegistry extensionRegistry;
  @Inject private OneOffRequestContext requestContext;

  /** Rejects commits uploaded to change refs while armed (simulates Gerrit refusing insertion). */
  private static final class FailingValidator implements CommitValidationListener {
    final AtomicBoolean armed = new AtomicBoolean(true);

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent e)
        throws CommitValidationException {
      if (armed.get() && e.command.getRefName().startsWith("refs/changes/")) {
        throw new CommitValidationException("injected failure");
      }
      return List.of();
    }
  }

  private SyncService syncService() {
    return plugin.getSysInjector().getInstance(SyncService.class);
  }

  @Test
  public void rejectedInsertionIsReportedAndRecoverable() throws Exception {
    enable();
    FailingValidator v = new FailingValidator();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    var tip = commits(repo, "a", 2);
    try (Registration reg = extensionRegistry.newRegistration().add(v)) {
      PushResult r = push(repo, FEATURE);
      assertOk(r, FEATURE);
      assertThat(refValue(FEATURE)).isEqualTo(tip);
      assertThat(joined(r)).contains("NOT synchronized");
      assertThat(joined(r)).contains("injected failure");
      assertThat(changes()).isEmpty();
      Lifecycle lc = lifecycle(FEATURE);
      assertThat(lc.syncState).isEqualTo(Lifecycle.SyncState.FAILED);
      assertThat(lc.pending).isNull();
      assertThat(status(user, FEATURE).getEntityContent()).contains("injected failure");
      v.armed.set(false);
      syncViaRest(user, FEATURE, new PostSync.Input());
    }
    assertThat(changes()).hasSize(1);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.SYNCED);
  }

  @Test
  public void crashAfterReservationBeforeInsertion() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    setConfig(null);
    push(repo, FEATURE);
    enable();
    // Simulate a reservation persisted by a process that died before inserting the Change.
    int reserved = sequences.nextChangeId();
    try (Repository r = repoManager.openRepository(project)) {
      Lifecycle lc = new Lifecycle();
      lc.id = "crashed";
      lc.project = project.get();
      lc.sourceRef = FEATURE;
      lc.targetRef = "refs/heads/master";
      lc.changeKey = "I" + "1".repeat(40);
      lc.lastActor = user.id().get();
      lc.syncState = Lifecycle.SyncState.PENDING;
      lc.pending = new Lifecycle.Pending();
      lc.pending.op = "create";
      lc.pending.changeNumber = reserved;
      lc.pending.patchSet = 1;
      lc.pending.commit = refValue(FEATURE).name();
      stateStore.saveLifecycle(
          r, project, lc, stateStore.loadLifecycle(r, FEATURE).refId(), "test");
    }
    SyncResult res = syncAs(user.id());
    assertThat(res.kind()).isEqualTo(SyncResult.Kind.CREATED);
    assertThat(changes()).hasSize(1);
    assertThat(lifecycle(FEATURE).pending).isNull();
    assertThat(lifecycle(FEATURE).id).isEqualTo("crashed");
  }

  @Test
  public void crashAfterInsertionBeforeCompletionCreatesNoDuplicate() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    push(repo, FEATURE);
    int n = onlyChange();
    // Rewind the lifecycle to the reserved-but-unconfirmed state.
    try (Repository r = repoManager.openRepository(project)) {
      StateStore.Loaded<Lifecycle> l = stateStore.loadLifecycle(r, FEATURE);
      Lifecycle lc = l.value();
      lc.pending = new Lifecycle.Pending();
      lc.pending.op = "create";
      lc.pending.changeNumber = n;
      lc.pending.patchSet = 1;
      lc.pending.commit = lc.lastSyntheticCommit;
      lc.pending.sourceId = lc.lastSourceId;
      lc.pending.targetId = lc.lastTargetId;
      lc.changeNumber = 0;
      lc.lastSyntheticPatchSet = 0;
      lc.syncState = Lifecycle.SyncState.PENDING;
      stateStore.saveLifecycle(r, project, lc, l.refId(), "test");
    }
    SyncResult res = syncAs(user.id());
    assertThat(res.kind()).isEqualTo(SyncResult.Kind.NOOP);
    assertThat(changes()).hasSize(1);
    assertThat(change(n).revisions).hasSize(1);
    assertThat(lifecycle(FEATURE).changeNumber).isEqualTo(n);
  }

  @Test
  public void recoveryServiceRetriesFailedSync() throws Exception {
    enable();
    FailingValidator v = new FailingValidator();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    try (Registration reg = extensionRegistry.newRegistration().add(v)) {
      push(repo, FEATURE);
      v.armed.set(false);
      // A definitive rejection is not retried automatically ...
      Lifecycle lc = lifecycle(FEATURE);
      assertThat(lc.attempts).isGreaterThan(1000);
    }
    // ... but a transient failure is: simulate one.
    try (Repository r = repoManager.openRepository(project)) {
      StateStore.Loaded<Lifecycle> l = stateStore.loadLifecycle(r, FEATURE);
      l.value().attempts = 1;
      l.value().nextAttemptAfter = null;
      stateStore.saveLifecycle(r, project, l.value(), l.refId(), "test");
    }
    int backlog = plugin.getSysInjector().getInstance(RecoveryService.class).runOnce();
    assertThat(backlog).isEqualTo(1);
    waitFor(() -> changes().size() == 1);
    waitFor(() -> lifecycle(FEATURE).syncState == Lifecycle.SyncState.SYNCED);
  }

  @Test
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.threads",
      value = "1")
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.maxQueued",
      value = "4")
  public void queueLimitAppliesToAllTasksAndReservesRoomForPushes() throws Exception {
    var executor =
        plugin
            .getSysInjector()
            .getInstance(io.github.quentinbtd.gerrit.branchreview.sync.SyncExecutor.class);
    var release = new java.util.concurrent.CountDownLatch(1);
    var ran = new java.util.concurrent.atomic.AtomicInteger();
    Runnable blocked =
        () -> {
          try {
            release.await();
            ran.incrementAndGet();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    var bg = io.github.quentinbtd.gerrit.branchreview.sync.SyncExecutor.Priority.BACKGROUND;
    int background = 0;
    for (int i = 0; i < 10; i++) {
      if (executor.execute(blocked, bg)) {
        background++;
      }
    }
    int foreground = 0;
    for (int i = 0; i < 10; i++) {
      if (executor.execute(blocked)) {
        foreground++;
      }
    }
    assertThat(background).isEqualTo(2);
    assertThat(foreground).isEqualTo(2);
    assertThat(executor.execute(blocked)).isFalse();
    release.countDown();
    waitFor(() -> ran.get() == 4);
    waitFor(() -> executor.execute(() -> {}));
  }

  @Test
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.threads",
      value = "1")
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.maxQueued",
      value = "2")
  public void healthyReviewsDoNotStarveFailedOnes() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    // Many healthy lifecycles sorted before the failed one ("refs/heads/feature/a..").
    for (String name : new String[] {"aa", "ab", "ac"}) {
      repo.reset(refValue("refs/heads/master"));
      commits(repo, name, 1);
      push(repo, "refs/heads/feature/" + name);
    }
    repo.reset(refValue("refs/heads/master"));
    commits(repo, "z", 1);
    FailingValidator v = new FailingValidator();
    String failed = "refs/heads/feature/zz";
    try (Registration reg = extensionRegistry.newRegistration().add(v)) {
      push(repo, failed);
    }
    try (Repository r = repoManager.openRepository(project)) {
      StateStore.Loaded<Lifecycle> l = stateStore.loadLifecycle(r, failed);
      l.value().attempts = 1;
      l.value().nextAttemptAfter = null;
      stateStore.saveLifecycle(r, project, l.value(), l.refId(), "test");
    }
    // One background slot (maxQueued 2 / 2): it must go to the failed lifecycle.
    plugin.getSysInjector().getInstance(RecoveryService.class).runOnce();
    waitFor(() -> lifecycle(failed).syncState == Lifecycle.SyncState.SYNCED);
    assertThat(changes()).hasSize(4);
  }

  @Test
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.threads",
      value = "1")
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.maxQueued",
      value = "1")
  public void droppedEditEventIsRecovered() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    push(repo, FEATURE);
    int n = onlyChange();
    var before = refValue(FEATURE);
    var executor =
        plugin
            .getSysInjector()
            .getInstance(io.github.quentinbtd.gerrit.branchreview.sync.SyncExecutor.class);
    var release = new java.util.concurrent.CountDownLatch(1);
    assertThat(
            executor.execute(
                () -> {
                  try {
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }))
        .isTrue();
    try {
      // The edit event cannot be queued.
      var edit = gApi.changes().id(project.get(), n).edit();
      edit.create();
      edit.modifyFile("a/file1.txt", com.google.gerrit.common.RawInputUtil.create("edited\n"));
      edit.publish();
    } finally {
      release.countDown();
    }
    waitFor(() -> executor.execute(() -> {}));
    assertThat(refValue(FEATURE)).isEqualTo(before);
    int backlog = plugin.getSysInjector().getInstance(RecoveryService.class).runOnce();
    assertThat(backlog).isEqualTo(1);
    waitFor(() -> !refValue(FEATURE).equals(before));
    waitFor(() -> refValue(FEATURE).name().equals(lifecycle(FEATURE).lastSourceId));
    assertThat(fileAt(refValue(FEATURE), "a/file1.txt")).isEqualTo("edited\n");
    // Nothing left to do afterwards.
    assertThat(plugin.getSysInjector().getInstance(RecoveryService.class).runOnce()).isEqualTo(0);
  }

  @Test
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.stopGraceSeconds",
      value = "1")
  public void stopWaitsForAnInFlightSynchronizationEvenWhenInterrupted() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    setConfig(null);
    push(repo, FEATURE);
    enable();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    // Slow, non-interruptible work inside the Change insertion.
    CommitValidationListener slow =
        new CommitValidationListener() {
          @Override
          public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent e) {
            if (e.command.getRefName().startsWith("refs/changes/")) {
              entered.countDown();
              while (true) {
                try {
                  release.await();
                  break;
                } catch (InterruptedException ex) {
                  interrupted.set(true);
                }
              }
            }
            return List.of();
          }
        };
    var executor = plugin.getSysInjector().getInstance(SyncExecutor.class);
    try (Registration reg = extensionRegistry.newRegistration().add(slow)) {
      var sync =
          executor
              .submit(
                  project, FEATURE, user.id(), SyncService.Trigger.MANUAL, SyncService.Options.NONE)
              .orElseThrow();
      assertThat(entered.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      Thread stopper = new Thread(executor::stop);
      stopper.start();
      // Past the grace period the worker is interrupted, but stop() must keep waiting.
      waitFor(interrupted::get);
      stopper.join(2000);
      assertThat(stopper.isAlive()).isTrue();
      release.countDown();
      stopper.join(60_000);
      assertThat(stopper.isAlive()).isFalse();
      assertThat(sync.isDone()).isTrue();
    }
    assertThat(changes().size()).isAtMost(1);
  }

  @Test
  public void stopWaitsForAnInFlightManualSyncRequest() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    setConfig(null);
    push(repo, FEATURE);
    enable();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    CommitValidationListener slow =
        new CommitValidationListener() {
          @Override
          public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent e) {
            if (e.command.getRefName().startsWith("refs/changes/")) {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
            }
            return List.of();
          }
        };
    var executor = plugin.getSysInjector().getInstance(SyncExecutor.class);
    var response = new java.util.concurrent.atomic.AtomicReference<String>();
    try (Registration reg = extensionRegistry.newRegistration().add(slow)) {
      // A real REST request on its own request thread.
      Thread request =
          new Thread(
              () -> {
                try {
                  response.set(syncViaRest(user, FEATURE, new PostSync.Input()));
                } catch (Exception ex) {
                  response.set("error: " + ex);
                }
              });
      request.start();
      assertThat(entered.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      Thread stopper = new Thread(executor::stop);
      stopper.start();
      stopper.join(2000);
      assertThat(stopper.isAlive()).isTrue();
      release.countDown();
      stopper.join(60_000);
      assertThat(stopper.isAlive()).isFalse();
      request.join(60_000);
    }
    assertThat(response.get()).contains("\"result\":\"created\"");
    assertThat(changes()).hasSize(1);
    // Requests after the stop are refused rather than run outside the executor.
    assertThat(syncViaRest(user, FEATURE, new PostSync.Input())).contains("plugin stopping");
  }

  @Test
  public void concurrentSynchronizationCreatesOneChange() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    setConfig(null);
    push(repo, FEATURE);
    enable();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<SyncResult>> fs = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        fs.add(pool.submit(() -> syncAs(user.id())));
      }
      int created = 0;
      for (Future<SyncResult> f : fs) {
        if (f.get().kind() == SyncResult.Kind.CREATED) {
          created++;
        }
      }
      assertThat(created).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(changes()).hasSize(1);
  }

  @Test
  public void sourceAdvancingDuringProcessingIsPickedUp() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    push(repo, FEATURE);
    int n = onlyChange();
    // A delayed, older event must sync to the current branch state, not to the event's OID.
    var newer = serverCommit(FEATURE, "z.txt", "z\n");
    SyncResult res = syncAs(user.id());
    assertThat(res.kind()).isEqualTo(SyncResult.Kind.UPDATED);
    assertThat(lifecycle(FEATURE).lastSourceId).isEqualTo(newer.name());
    assertThat(syncAs(user.id()).kind()).isEqualTo(SyncResult.Kind.NOOP);
    assertThat(change(n).revisions).hasSize(2);
  }

  private SyncResult syncAs(Account.Id id) throws Exception {
    try (ManualRequestContext ctx = requestContext.openAs(id)) {
      return syncService()
          .sync(
              project,
              FEATURE,
              ctx.getUser().asIdentifiedUser(),
              SyncService.Trigger.MANUAL,
              SyncService.Options.NONE);
    }
  }
}
