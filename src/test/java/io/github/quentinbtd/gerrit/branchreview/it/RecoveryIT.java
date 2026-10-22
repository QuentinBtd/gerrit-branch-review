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
