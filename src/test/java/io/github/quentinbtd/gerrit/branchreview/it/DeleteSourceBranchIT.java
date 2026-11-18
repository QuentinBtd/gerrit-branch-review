// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.RestSession;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.extensions.api.projects.ConfigInput;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.client.ProjectState;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.inject.Inject;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

public class DeleteSourceBranchIT extends AbstractBranchReviewIT {
  private static final String FEATURE = "refs/heads/feature/delete-me";
  private static final String ACTION = "branch-review~delete-source-branch";

  @Inject private RequestScopeOperations requestScopeOperations;

  private TestRepository<InMemoryRepository> repo;
  private RevCommit tip;
  private int number;

  @Before
  public void pushFeatureBranch() throws Exception {
    enable();
    repo = cloneAs(user);
    tip = commits(repo, "a", 2);
    assertOk(push(repo, FEATURE), FEATURE);
    number = onlyChange();
  }

  private boolean offered() throws Exception {
    var actions =
        gApi.changes().id(project.get(), number).get(ListChangesOption.CHANGE_ACTIONS).actions;
    return actions != null && actions.containsKey(ACTION);
  }

  private RestResponse delete(RestSession session) throws Exception {
    return session.post("/changes/" + number + "/" + ACTION);
  }

  @Test
  public void notOfferedWhileTheChangeIsOpen() throws Exception {
    assertThat(offered()).isFalse();
    delete(adminRestSession).assertConflict();
    assertThat(refValue(FEATURE)).isEqualTo(tip);
  }

  @Test
  public void deletesTheBranchOfAnAbandonedChange() throws Exception {
    gApi.changes().id(project.get(), number).abandon();
    assertThat(offered()).isTrue();

    delete(adminRestSession).assertOK();

    assertThat(refValue(FEATURE)).isNull();
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.SOURCE_DELETED);
    assertThat(offered()).isFalse();
    assertThat(
            gApi.changes().id(project.get(), number).messages().stream()
                .map((ChangeMessageInfo m) -> m.message))
        .contains(
            "Deleted source branch feature/delete-me (was at " + tip.abbreviate(7).name() + ").");
  }

  @Test
  public void deletesTheBranchOfAMergedChange() throws Exception {
    approveAndSubmit(number);
    waitFor(() -> lifecycle(FEATURE).merge != null && lifecycle(FEATURE).merge.submitter != 0);
    assertThat(offered()).isTrue();

    delete(adminRestSession).assertOK();

    assertThat(refValue(FEATURE)).isNull();
    assertThat(lifecycle(FEATURE).merge.cleanup).isEqualTo("deleted");
  }

  @Test
  public void keepsABranchWithCommitsTheChangeDoesNotHave() throws Exception {
    gApi.changes().id(project.get(), number).abandon();
    // Pushed after the abandon: not synchronized into the Change.
    RevCommit newer = commits(repo, "b", 1);
    push(repo, FEATURE);
    assertThat(refValue(FEATURE)).isEqualTo(newer);

    assertThat(offered()).isFalse();
    RestResponse r = delete(adminRestSession);
    r.assertConflict();
    assertThat(r.getEntityContent()).contains("has commits that are not in this Change");
    assertThat(refValue(FEATURE)).isEqualTo(newer);
  }

  @Test
  public void refusedInAReadOnlyProject() throws Exception {
    gApi.changes().id(project.get(), number).abandon();
    ConfigInput in = new ConfigInput();
    in.state = ProjectState.READ_ONLY;
    gApi.projects().name(project.get()).config(in);

    assertThat(offered()).isFalse();
    RestResponse r = delete(adminRestSession);
    r.assertConflict();
    assertThat(r.getEntityContent()).contains("does not permit write");
    assertThat(refValue(FEATURE)).isEqualTo(tip);
  }

  @Test
  public void refusedWhenTheChangeIsRestoredWhileTheDeletionWaits() throws Exception {
    gApi.changes().id(project.get(), number).abandon();
    Lock lock = plugin.getSysInjector().getInstance(SyncService.class).lockFor(project, FEATURE);
    ExecutorService other = Executors.newSingleThreadExecutor();
    Future<RestResponse> pending;
    lock.lock();
    try {
      pending = other.submit(() -> delete(adminRestSession));
      // The deletion passed its first check and now waits for the branch lock.
      waitFor(() -> ((ReentrantLock) lock).hasQueuedThreads());
      gApi.changes().id(project.get(), number).restore();
    } finally {
      lock.unlock();
    }
    try {
      RestResponse r = pending.get();
      r.assertConflict();
      assertThat(r.getEntityContent()).contains("still open");
    } finally {
      other.shutdown();
    }
    assertThat(refValue(FEATURE)).isEqualTo(tip);
  }

  @Test
  public void notOfferedWithoutDeletePermission() throws Exception {
    // refs/heads/bugfix/* grants push without force and no delete permission.
    String bugfix = "refs/heads/bugfix/delete-me";
    RevCommit bugfixTip = commits(repo, "c", 1);
    assertOk(push(repo, bugfix), bugfix);
    number = lifecycle(bugfix).changeNumber;
    gApi.changes().id(project.get(), number).abandon();

    requestScopeOperations.setApiUser(user.id());
    assertThat(offered()).isFalse();
    requestScopeOperations.setApiUser(admin.id());
    delete(userRestSession).assertForbidden();
    assertThat(refValue(bugfix)).isEqualTo(bugfixTip);
  }
}
