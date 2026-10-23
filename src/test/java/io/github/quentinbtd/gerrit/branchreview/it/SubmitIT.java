// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.entities.Permission;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.server.group.SystemGroupBackend;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import java.util.List;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.junit.Test;

public class SubmitIT extends AbstractBranchReviewIT {
  @com.google.inject.Inject
  private com.google.gerrit.acceptance.ExtensionRegistry extensionRegistry;

  @com.google.inject.Inject
  private com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations
      requestScopeOperations;

  @com.google.inject.Inject
  private com.google.gerrit.server.permissions.PermissionBackend permissionBackend;

  private static final String FEATURE = "refs/heads/feature/submit-me";
  private static final String MASTER = "refs/heads/master";

  private RevCommit pushFeature(TestRepository<InMemoryRepository> repo, int n) throws Exception {
    RevCommit tip = commits(repo, "s", n);
    assertOk(push(repo, FEATURE), FEATURE);
    return tip;
  }

  private void assertOneContributionCommit(ObjectId before, RevCommit sourceTip) throws Exception {
    ObjectId after = refValue(MASTER);
    List<RevCommit> added = between(before, after);
    assertThat(added).hasSize(1);
    assertThat(added.get(0).getParentCount()).isEqualTo(1);
    assertThat(added.get(0).getParent(0)).isEqualTo(before);
    assertThat(isAncestor(sourceTip, after)).isFalse();
    for (RevCommit c : between(refValue(MASTER), sourceTip)) {
      assertThat(isAncestor(c, after)).isFalse();
    }
    assertThat(fileAt(after, "s/file1.txt")).isEqualTo("s content 1\n");
  }

  private void submitWith(SubmitType type) throws Exception {
    enable();
    setSubmitType(type);
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit tip = pushFeature(repo, 3);
    ObjectId before = refValue(MASTER);
    approveAndSubmit(onlyChange());
    assertThat(change(onlyChange()).status).isEqualTo(ChangeStatus.MERGED);
    assertOneContributionCommit(before, tip);
  }

  @Test
  public void fastForwardOnly() throws Exception {
    submitWith(SubmitType.FAST_FORWARD_ONLY);
  }

  @Test
  public void rebaseIfNecessary() throws Exception {
    submitWith(SubmitType.REBASE_IF_NECESSARY);
  }

  @Test
  public void rebaseAlways() throws Exception {
    submitWith(SubmitType.REBASE_ALWAYS);
  }

  @Test
  public void cherryPick() throws Exception {
    submitWith(SubmitType.CHERRY_PICK);
  }

  @Test
  public void mergeIfNecessaryWhenFastForwardPossible() throws Exception {
    submitWith(SubmitType.MERGE_IF_NECESSARY);
  }

  @Test
  public void mergeIfNecessaryBlockedWhenTargetMoved() throws Exception {
    enable();
    setSubmitType(SubmitType.MERGE_IF_NECESSARY);
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 2);
    serverCommit(MASTER, "other.txt", "other\n");
    int n = onlyChange();
    ResourceConflictException e =
        assertThrows(ResourceConflictException.class, () -> approveAndSubmit(n));
    assertThat(e).hasMessageThat().contains("MERGE_IF_NECESSARY would add a merge commit");
    assertThat(change(n).status).isEqualTo(ChangeStatus.NEW);
  }

  @Test
  public void manualSyncRefreshesAMovedTarget() throws Exception {
    enable();
    setSubmitType(SubmitType.MERGE_IF_NECESSARY);
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit tip = pushFeature(repo, 2);
    int n = onlyChange();
    RevCommit other = serverCommit(MASTER, "other.txt", "other\n");
    String out =
        syncViaRest(
            user, FEATURE, new io.github.quentinbtd.gerrit.branchreview.api.PostSync.Input());
    assertThat(out).contains("\"result\":\"updated\"");
    var c = change(n);
    assertThat(c.revisions).hasSize(2);
    assertThat(c.revisions.get(c.currentRevision).commit.parents.get(0).commit)
        .isEqualTo(other.name());
    // The source did not change and a second sync is a no-op.
    assertThat(
            syncViaRest(
                user, FEATURE, new io.github.quentinbtd.gerrit.branchreview.api.PostSync.Input()))
        .contains("\"result\":\"noop\"");
    approveAndSubmit(n);
    assertOneContributionCommit(other, tip);
  }

  private int pushBranch(TestRepository<InMemoryRepository> repo, String ref, String prefix)
      throws Exception {
    repo.reset(refValue(MASTER));
    commits(repo, prefix, 1);
    assertOk(push(repo, ref), ref);
    return lifecycle(ref).changeNumber;
  }

  @Test
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "change.submitWholeTopic",
      value = "true")
  public void topicSubmitThatWouldMergeIsRejected() throws Exception {
    enable();
    setSubmitType(SubmitType.MERGE_IF_NECESSARY);
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    int a = pushBranch(repo, "refs/heads/feature/one", "one");
    int b = pushBranch(repo, "refs/heads/feature/two", "two");
    ObjectId before = refValue(MASTER);
    for (int n : new int[] {a, b}) {
      var api = gApi.changes().id(project.get(), n);
      api.topic("together");
      api.setReadyForReview();
      api.current().review(com.google.gerrit.extensions.api.changes.ReviewInput.approve());
    }
    ResourceConflictException e =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(project.get(), a).current().submit());
    assertThat(e).hasMessageThat().contains("through a merge commit");
    assertThat(refValue(MASTER)).isEqualTo(before);
    assertThat(change(a).status).isEqualTo(ChangeStatus.NEW);
    assertThat(change(b).status).isEqualTo(ChangeStatus.NEW);
  }

  @Test
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "change.submitWholeTopic",
      value = "true")
  public void topicSubmitWithRebaseAddsOneCommitPerReview() throws Exception {
    enable();
    setSubmitType(SubmitType.REBASE_IF_NECESSARY);
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    int a = pushBranch(repo, "refs/heads/feature/one", "one");
    int b = pushBranch(repo, "refs/heads/feature/two", "two");
    ObjectId before = refValue(MASTER);
    for (int n : new int[] {a, b}) {
      var api = gApi.changes().id(project.get(), n);
      api.topic("together");
      api.setReadyForReview();
      api.current().review(com.google.gerrit.extensions.api.changes.ReviewInput.approve());
    }
    gApi.changes().id(project.get(), a).current().submit();
    List<RevCommit> added = between(before, refValue(MASTER));
    assertThat(added).hasSize(2);
    for (RevCommit c : added) {
      assertThat(c.getParentCount()).isEqualTo(1);
    }
  }

  @Test
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "change.submitWholeTopic",
      value = "true")
  public void topicWithAnOrdinaryChildStillCannotAddAMerge() throws Exception {
    enable();
    setSubmitType(SubmitType.MERGE_IF_NECESSARY);
    TestRepository<InMemoryRepository> repo = cloneAs(admin);
    int a = pushBranch(repo, "refs/heads/feature/one", "one");
    int b = pushBranch(repo, "refs/heads/feature/two", "two");
    // An ordinary review on top of b's patch set.
    repo.git()
        .fetch()
        .setRemote("origin")
        .setRefSpecs(change(b).revisions.get(change(b).currentRevision).ref + ":refs/heads/b")
        .call();
    repo.reset(ObjectId.fromString(change(b).currentRevision));
    repo.branch("HEAD")
        .commit()
        .author(admin.newIdent())
        .committer(admin.newIdent())
        .insertChangeId()
        .message("ordinary child")
        .add("child.txt", "c\n")
        .create();
    assertOk(
        push(repo, "HEAD:refs/for/master%topic=together", false), "refs/for/master%topic=together");
    ObjectId before = refValue(MASTER);
    for (var c : changes()) {
      var api = gApi.changes().id(project.get(), c._number);
      api.topic("together");
      if (Boolean.TRUE.equals(api.get().workInProgress)) {
        api.setReadyForReview();
      }
      api.current().review(com.google.gerrit.extensions.api.changes.ReviewInput.approve());
    }
    ResourceConflictException e =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(project.get(), a).current().submit());
    assertThat(e).hasMessageThat().contains("through a merge commit");
    assertThat(refValue(MASTER)).isEqualTo(before);
  }

  @Test
  public void maintenanceMergeOfASubmittedReviewIsAllowed() throws Exception {
    enable();
    ObjectId start = refValue(MASTER);
    serverCommit("refs/heads/stable", "stable.txt", "s\n");
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 1);
    approveAndSubmit(onlyChange());
    waitFor(() -> lifecycle(FEATURE).status == Lifecycle.Status.MERGED);
    assertThat(start).isNotNull();
    // An ordinary review merging master (with the submitted review commit) into stable.
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allow(Permission.PUSH_MERGE)
                .ref("refs/for/refs/heads/stable")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    TestRepository<InMemoryRepository> admin = cloneAs(this.admin);
    admin
        .git()
        .fetch()
        .setRemote("origin")
        .setRefSpecs("+refs/heads/*:refs/remotes/origin/*")
        .call();
    RevCommit merge =
        admin
            .commit()
            .parent(admin.getRevWalk().parseCommit(refValue("refs/heads/stable")))
            .parent(admin.getRevWalk().parseCommit(refValue(MASTER)))
            .author(this.admin.newIdent())
            .committer(this.admin.newIdent())
            .insertChangeId()
            .message("Merge master into stable")
            .add("stable.txt", "s\n")
            .add("s/file1.txt", "s content 1\n")
            .create();
    assertOk(push(admin, merge.name() + ":refs/for/stable", false), "refs/for/stable");
    var mergeChange =
        gApi.changes().query("project:" + project.get() + " branch:stable").get().get(0)._number;
    approveAndSubmit(mergeChange);
    assertThat(change(mergeChange).status).isEqualTo(ChangeStatus.MERGED);
  }

  /** Rejects deletions of feature branches, like a site branch-protection plugin. */
  private static final class NoFeatureDeletion
      implements com.google.gerrit.server.git.validators.RefOperationValidationListener {
    @Override
    public List<com.google.gerrit.server.git.validators.ValidationMessage> onRefOperation(
        com.google.gerrit.server.events.RefReceivedEvent e)
        throws com.google.gerrit.server.validators.ValidationException {
      if (e.command.getType() == org.eclipse.jgit.transport.ReceiveCommand.Type.DELETE
          && e.getRefName().startsWith("refs/heads/feature/")) {
        throw new com.google.gerrit.server.validators.ValidationException(
            "feature branches are kept by policy");
      }
      return List.of();
    }
  }

  @Test
  public void cleanupRespectsDeletionValidators() throws Exception {
    enableCleanup();
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allow(Permission.DELETE)
                .ref("refs/heads/feature/*")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit tip = pushFeature(repo, 1);
    try (var reg = extensionRegistry.newRegistration().add(new NoFeatureDeletion())) {
      // The policy rejects a normal deletion ...
      var r = push(repo, ":" + FEATURE, true);
      assertThat(r.getRemoteUpdate(FEATURE).getStatus())
          .isNotEqualTo(org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK);
      // ... and therefore the cleanup too.
      approveAndSubmit(onlyChange());
      waitFor(
          () ->
              lifecycle(FEATURE).merge != null
                  && !"pending".equals(lifecycle(FEATURE).merge.cleanup)
                  && !"none".equals(lifecycle(FEATURE).merge.cleanup));
    }
    Lifecycle lc = lifecycle(FEATURE);
    assertThat(lc.merge.cleanup).isEqualTo("skipped");
    assertThat(lc.merge.cleanupDetail).contains("feature branches are kept by policy");
    assertThat(refValue(FEATURE)).isEqualTo(tip);
  }

  @Test
  public void configuredTargetChangeBlocksSubmitWithoutPush() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 1);
    int n = onlyChange();
    serverCommit("refs/heads/stable", "s.txt", "s\n");
    setConfig(
        DEFAULT_CONFIG.replace(
            "targetBranch = refs/heads/master", "targetBranch = refs/heads/stable"));
    ResourceConflictException e =
        assertThrows(ResourceConflictException.class, () -> approveAndSubmit(n));
    assertThat(e)
        .hasMessageThat()
        .contains("is now refs/heads/stable but this review targets refs/heads/master");
    assertThat(change(n).status).isEqualTo(ChangeStatus.NEW);
  }

  @Test
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.threads",
      value = "1")
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.maxQueued",
      value = "1")
  public void cleanupStillHappensWhenTheMergeEventWasDropped() throws Exception {
    enableCleanup();
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allow(Permission.DELETE)
                .ref("refs/heads/feature/*")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 1);
    int n = onlyChange();
    var executor =
        plugin
            .getSysInjector()
            .getInstance(io.github.quentinbtd.gerrit.branchreview.sync.SyncExecutor.class);
    var release = new java.util.concurrent.CountDownLatch(1);
    try {
      // Push completion can precede the previous worker's admission-slot release.
      waitFor(
          () ->
              executor.execute(
                  () -> {
                    try {
                      release.await();
                    } catch (InterruptedException ex) {
                      Thread.currentThread().interrupt();
                    }
                  }));
      approveAndSubmit(n);
      assertThat(lifecycle(FEATURE).merge).isNull();
    } finally {
      release.countDown();
    }
    // With one worker, this barrier runs after the blocker has released its admission slot.
    executor.pool().submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
    plugin
        .getSysInjector()
        .getInstance(io.github.quentinbtd.gerrit.branchreview.recovery.RecoveryService.class)
        .runOnce();
    // Ref deletion happens before the final lifecycle write; wait for both effects.
    waitFor(
        () -> {
          Lifecycle current = lifecycle(FEATURE);
          return current.merge != null
              && "deleted".equals(current.merge.cleanup)
              && refValue(FEATURE) == null;
        });
    Lifecycle lc = lifecycle(FEATURE);
    assertThat(lc.merge.submitter).isEqualTo(admin.id().get());
    assertThat(lc.merge.cleanup).isEqualTo("deleted");
  }

  @Test
  public void mergeAlwaysIsBlocked() throws Exception {
    enable();
    setSubmitType(SubmitType.MERGE_ALWAYS);
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 2);
    int n = onlyChange();
    ResourceConflictException e =
        assertThrows(ResourceConflictException.class, () -> approveAndSubmit(n));
    assertThat(e).hasMessageThat().contains("MERGE_ALWAYS would add more than one commit");
  }

  @Test
  public void targetAdvancesAfterPatchSetUnrelatedWorkIsKept() throws Exception {
    enable();
    setSubmitType(SubmitType.REBASE_IF_NECESSARY);
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit tip = pushFeature(repo, 2);
    RevCommit other = serverCommit(MASTER, "other.txt", "other\n");
    approveAndSubmit(onlyChange());
    assertOneContributionCommit(other, tip);
    assertThat(fileAt(refValue(MASTER), "other.txt")).isEqualTo("other\n");
  }

  @Test
  public void targetAdvancedBeforeSquashIsPreserved() throws Exception {
    enable();
    setSubmitType(SubmitType.FAST_FORWARD_ONLY);
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit start = repo.getRevWalk().parseCommit(refValue(MASTER));
    repo.reset(start);
    // Target changes another file after the branch point but before the first push.
    RevCommit other = serverCommit(MASTER, "other.txt", "target work\n");
    RevCommit tip = pushFeature(repo, 2);
    String rev = change(onlyChange()).currentRevision;
    assertThat(fileAt(ObjectId.fromString(rev), "other.txt")).isEqualTo("target work\n");
    approveAndSubmit(onlyChange());
    assertOneContributionCommit(other, tip);
    assertThat(fileAt(refValue(MASTER), "other.txt")).isEqualTo("target work\n");
  }

  @Test
  public void staleSourceBlocksSubmit() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 1);
    // The source moves without synchronization (e.g. a failed or still running sync).
    serverCommit(FEATURE, "late.txt", "late\n");
    int n = onlyChange();
    ResourceConflictException e =
        assertThrows(ResourceConflictException.class, () -> approveAndSubmit(n));
    assertThat(e)
        .hasMessageThat()
        .contains("has advanced since the current patch set was generated");
  }

  @Test
  public void reviewGatesStillApply() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 1);
    int n = onlyChange();
    gApi.changes().id(project.get(), n).setReadyForReview();
    assertThrows(
        ResourceConflictException.class,
        () -> gApi.changes().id(project.get(), n).current().submit());
  }

  @Test
  public void cleanupIsOffByDefault() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit tip = pushFeature(repo, 1);
    approveAndSubmit(onlyChange());
    waitFor(() -> lifecycle(FEATURE).status == Lifecycle.Status.MERGED);
    assertThat(refValue(FEATURE)).isEqualTo(tip);
  }

  private void enableCleanup() throws Exception {
    setConfig(DEFAULT_CONFIG + "  deleteSourceBranchOnSubmit = true\n");
  }

  @Test
  public void cleanupDeletesExactSubmittedSourceWhenAuthorized() throws Exception {
    enableCleanup();
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allow(Permission.DELETE)
                .ref("refs/heads/feature/*")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 2);
    approveAndSubmit(onlyChange());
    // The ref is deleted before the final cleanup result is persisted.
    waitFor(
        () -> {
          Lifecycle current = lifecycle(FEATURE);
          return current.merge != null
              && "deleted".equals(current.merge.cleanup)
              && refValue(FEATURE) == null;
        });
    Lifecycle lc = lifecycle(FEATURE);
    assertThat(lc.merge.cleanup).isEqualTo("deleted");
    // Repeated merge handling is idempotent.
    plugin
        .getSysInjector()
        .getInstance(io.github.quentinbtd.gerrit.branchreview.submit.MergeHandler.class)
        .handleMerged(project, lc.changeNumber, admin.id().get(), null);
    assertThat(lifecycle(FEATURE).merge.cleanup).isEqualTo("deleted");
  }

  @Test
  public void cleanupSkippedWithoutDeletePermission() throws Exception {
    enableCleanup();
    // refs/heads/bugfix/* grants push without force and no delete permission.
    String bugfix = "refs/heads/bugfix/cleanup";
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit tip = commits(repo, "s", 1);
    assertOk(push(repo, bugfix), bugfix);
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allow(Permission.SUBMIT).ref("refs/heads/*").group(SystemGroupBackend.REGISTERED_USERS))
        .add(
            com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowLabel(
                    "Code-Review")
                .ref("refs/heads/*")
                .group(SystemGroupBackend.REGISTERED_USERS)
                .range(-2, 2))
        .update();
    assertThat(
            permissionBackend
                .absentUser(user.id())
                .project(project)
                .ref(bugfix)
                .test(com.google.gerrit.server.permissions.RefPermission.DELETE))
        .isFalse();
    // Submitted by a non-owner without delete rights on the source branch.
    requestScopeOperations.setApiUser(user.id());
    approveAndSubmit(onlyChange());
    requestScopeOperations.setApiUser(admin.id());
    waitFor(
        () ->
            lifecycle(bugfix).merge != null && !"pending".equals(lifecycle(bugfix).merge.cleanup));
    assertThat(lifecycle(bugfix).merge.submitter).isEqualTo(user.id().get());
    assertThat(lifecycle(bugfix).merge.cleanup).isEqualTo("skipped");
    assertThat(lifecycle(bugfix).merge.cleanupDetail).contains("no delete permission");
    assertThat(refValue(bugfix)).isEqualTo(tip);
  }

  @Test
  public void cleanupSkippedWhenSourceAdvanced() throws Exception {
    enableCleanup();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 1);
    int n = onlyChange();
    var api = gApi.changes().id(project.get(), n);
    api.setReadyForReview();
    api.current().review(com.google.gerrit.extensions.api.changes.ReviewInput.approve());
    api.current().submit();
    // Simulate a push that lands after submit but before cleanup ran.
    var mh =
        plugin
            .getSysInjector()
            .getInstance(io.github.quentinbtd.gerrit.branchreview.submit.MergeHandler.class);
    waitFor(
        () ->
            lifecycle(FEATURE).merge != null
                && !"pending".equals(lifecycle(FEATURE).merge.cleanup));
    assertThat(lifecycle(FEATURE).merge.cleanup).isEqualTo("deleted");
    {
      // The branch is recreated at a different commit; a retried cleanup must keep it.
      RevCommit late = serverCommit(FEATURE, "late.txt", "late\n");
      try (var r = repoManager.openRepository(project)) {
        var l = stateStore.loadLifecycle(r, FEATURE);
        l.value().merge.cleanup = "pending";
        stateStore.saveLifecycle(r, project, l.value(), l.refId(), "test");
      }
      mh.handleMerged(project, n, admin.id().get(), null);
      assertThat(lifecycle(FEATURE).merge.cleanup).isEqualTo("skipped");
      assertThat(refValue(FEATURE)).isEqualTo(late);
    }
  }

  @Test
  public void branchReuseAfterSubmitRequiresTargetBasedHistory() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 1);
    int first = onlyChange();
    approveAndSubmit(first);
    waitFor(() -> lifecycle(FEATURE).status == Lifecycle.Status.MERGED);

    // Continuing on the old history would re-submit already submitted work.
    commits(repo, "more", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("already submitted as Change " + first);
    assertThat(changes()).hasSize(1);
    assertThat(change(first).revisions).hasSize(1);

    // Restart from the target: a fresh lifecycle with only the new work.
    repo.git()
        .fetch()
        .setRemote("origin")
        .setRefSpecs("refs/heads/master:refs/remotes/origin/master")
        .call();
    repo.reset(refValue(MASTER));
    commits(repo, "next", 1);
    r = push(repo, "HEAD:" + FEATURE, true);
    assertThat(joined(r)).contains("created Change");
    assertThat(changes()).hasSize(2);
    Lifecycle lc = lifecycle(FEATURE);
    assertThat(lc.previousChangeNumber).isEqualTo(first);
    String rev = change(lc.changeNumber).currentRevision;
    assertThat(between(refValue(MASTER), ObjectId.fromString(rev))).hasSize(1);
    assertThat(fileAt(ObjectId.fromString(rev), "next/file1.txt")).isNotNull();
  }

  @Test
  public void pushAfterMergeNeverAddsPatchSetToMergedChange() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    pushFeature(repo, 1);
    int n = onlyChange();
    approveAndSubmit(n);
    commits(repo, "late", 1);
    push(repo, FEATURE);
    assertThat(change(n).revisions).hasSize(1);
  }
}
