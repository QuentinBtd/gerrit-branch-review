// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;

import com.google.gerrit.entities.Permission;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.server.group.SystemGroupBackend;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.Before;
import org.junit.Test;

/** Several target branches selected by source branch pattern ({@code [target]} sections). */
public class RoutingIT extends AbstractBranchReviewIT {
  private static final String ROUTED =
      """
      [branchReview]
        enabled = true
        targetBranch = refs/heads/master
        sourceBranchPattern = refs/heads/*
        excludeBranchPattern = refs/heads/private/*
      [target "refs/heads/develop"]
        sourceBranchPattern = refs/heads/dev/*
        sourceBranchPattern = refs/heads/*/feat/*
        sourceBranchPattern = refs/heads/*/chore/*
      [target "refs/heads/runtime"]
        sourceBranchPattern = refs/heads/run/*
        deleteSourceBranchOnSubmit = true
      """;

  private TestRepository<InMemoryRepository> repo;
  private ObjectId devTip;
  private ObjectId runTip;

  @Before
  public void setUpTargets() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allow(Permission.CREATE).ref("refs/heads/*").group(SystemGroupBackend.REGISTERED_USERS))
        .add(allow(Permission.PUSH).ref("refs/heads/*").group(SystemGroupBackend.REGISTERED_USERS))
        .add(
            allow(Permission.DELETE).ref("refs/heads/*").group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    devTip = serverCommit("refs/heads/develop", "dev.txt", "dev\n");
    runTip = serverCommit("refs/heads/runtime", "run.txt", "run\n");
    setConfig(ROUTED);
    repo = cloneAs(user);
    repo.git()
        .fetch()
        .setRemote("origin")
        .setRefSpecs("+refs/heads/*:refs/remotes/origin/*")
        .call();
  }

  private ObjectId pushFrom(ObjectId base, String ref, String prefix) throws Exception {
    repo.reset(repo.getRevWalk().parseCommit(base));
    RevCommit tip = commits(repo, prefix, 1);
    PushResult r = push(repo, ref);
    assertOk(r, ref);
    return tip;
  }

  @Test
  public void eachBranchGoesToItsRoute() throws Exception {
    ObjectId master = refValue("refs/heads/master");
    pushFrom(devTip, "refs/heads/dev/login", "a");
    pushFrom(devTip, "refs/heads/alice/feat/search", "b");
    pushFrom(runTip, "refs/heads/run/feat/x", "c");
    pushFrom(master, "refs/heads/feature/y", "d");
    PushResult excluded = push(repo, "HEAD:refs/heads/private/z", false);
    assertThat(pluginLines(excluded)).isEmpty();

    assertThat(change(lifecycle("refs/heads/dev/login").changeNumber).branch).isEqualTo("develop");
    assertThat(change(lifecycle("refs/heads/alice/feat/search").changeNumber).branch)
        .isEqualTo("develop");
    // run/* is more specific than */feat/*.
    assertThat(change(lifecycle("refs/heads/run/feat/x").changeNumber).branch).isEqualTo("runtime");
    assertThat(change(lifecycle("refs/heads/feature/y").changeNumber).branch).isEqualTo("master");
    assertThat(changes()).hasSize(4);

    // Each review is squashed onto its own target.
    var dev = change(lifecycle("refs/heads/dev/login").changeNumber);
    assertThat(dev.revisions.get(dev.currentRevision).commit.parents.get(0).commit)
        .isEqualTo(devTip.name());
    assertThat(change(lifecycle("refs/heads/alice/feat/search").changeNumber).subject)
        .isEqualTo("b commit 1");
  }

  @Test
  public void cleanupOverrideAppliesPerRoute() throws Exception {
    setSubmitType(SubmitType.REBASE_IF_NECESSARY);
    pushFrom(runTip, "refs/heads/run/fix", "r");
    pushFrom(devTip, "refs/heads/dev/fix", "d");
    approveAndSubmit(lifecycle("refs/heads/run/fix").changeNumber);
    approveAndSubmit(lifecycle("refs/heads/dev/fix").changeNumber);
    // The ref is deleted before the cleanup result is persisted: wait for both.
    waitFor(
        () -> {
          Lifecycle run = lifecycle("refs/heads/run/fix");
          return run.merge != null
              && "deleted".equals(run.merge.cleanup)
              && refValue("refs/heads/run/fix") == null;
        });
    // MERGED and the "none" cleanup outcome are saved together.
    waitFor(() -> lifecycle("refs/heads/dev/fix").status == Lifecycle.Status.MERGED);
    // The run route deletes its source branches, the dev route (default false) keeps them.
    assertThat(refValue("refs/heads/dev/fix")).isNotNull();
    assertThat(lifecycle("refs/heads/run/fix").merge.cleanup).isEqualTo("deleted");
    assertThat(lifecycle("refs/heads/dev/fix").merge.cleanup).isEqualTo("none");
  }

  /** Default route syncs nothing back; develop opts in, runtime explicitly opts out. */
  private static final String EDITS_ROUTED =
      ROUTED
          .replace(
              "excludeBranchPattern = refs/heads/private/*",
              "excludeBranchPattern = refs/heads/private/*\n  syncEditsToBranch = false")
          .replace(
              "sourceBranchPattern = refs/heads/*/chore/*",
              "sourceBranchPattern = refs/heads/*/chore/*\n  syncEditsToBranch = true");

  /** Project default on; the runtime route opts out. */
  private static final String EDITS_OFF_FOR_RUNTIME =
      ROUTED.replace(
          "deleteSourceBranchOnSubmit = true",
          "deleteSourceBranchOnSubmit = true\n  syncEditsToBranch = false");

  private void editInGerrit(int change, String path, String content) throws Exception {
    var edit = gApi.changes().id(project.get(), change).edit();
    edit.create();
    edit.modifyFile(path, com.google.gerrit.common.RawInputUtil.create(content));
    edit.publish();
  }

  /** Pushes one more commit on {@code ref}; fails if someone else moved the branch meanwhile. */
  private PushResult pushOnTop(String ref, ObjectId expectedTip, String prefix) throws Exception {
    repo.reset(repo.getRevWalk().parseCommit(expectedTip));
    commits(repo, prefix, 1);
    PushResult r = push(repo, ref);
    assertOk(r, ref);
    return r;
  }

  @Test
  // No automatic recovery pass: the edit must be carried over by the edit listener itself.
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.recoveryIntervalSeconds",
      value = "-1")
  public void routeOverrideEnablesEditTransferWhenTheProjectDefaultIsOff() throws Exception {
    setConfig(EDITS_ROUTED);
    ObjectId devStart = pushFrom(devTip, "refs/heads/dev/edit", "a");
    ObjectId mainStart = pushFrom(refValue("refs/heads/master"), "refs/heads/feature/edit", "b");
    int dev = lifecycle("refs/heads/dev/edit").changeNumber;
    int main = lifecycle("refs/heads/feature/edit").changeNumber;

    // Default route (off): the listener must not write the branch, so a push from the old tip is
    // still a fast-forward, and synchronization pauses instead of carrying the edit over.
    editInGerrit(main, "b/file1.txt", "edited on master route\n");
    PushResult r = pushOnTop("refs/heads/feature/edit", mainStart, "c");
    assertThat(joined(r)).contains("was not produced by branch-review");
    // The disabled path: never written back, resumed only by an explicit accept-divergence.
    assertThat(joined(r)).contains("then commit, push, and run:");
    assertThat(joined(r)).doesNotContain("applied the edits");
    assertThat(lifecycle("refs/heads/feature/edit").syncState)
        .isEqualTo(Lifecycle.SyncState.DIVERGED);

    // develop route (on): the edit lands on the source branch as one commit.
    editInGerrit(dev, "a/file1.txt", "edited on develop route\n");
    waitFor(() -> !refValue("refs/heads/dev/edit").equals(devStart));
    ObjectId tip = refValue("refs/heads/dev/edit");
    assertThat(fileAt(tip, "a/file1.txt")).isEqualTo("edited on develop route\n");
    assertThat(between(devStart, tip)).hasSize(1);
  }

  @Test
  public void routeOverrideDisablesEditTransferWhenTheProjectDefaultIsOn() throws Exception {
    setConfig(EDITS_OFF_FOR_RUNTIME);
    ObjectId runStart = pushFrom(runTip, "refs/heads/run/edit", "a");
    int run = lifecycle("refs/heads/run/edit").changeNumber;
    editInGerrit(run, "a/file1.txt", "edited on runtime route\n");
    // The push from the old tip succeeding proves nothing wrote the branch.
    PushResult r = pushOnTop("refs/heads/run/edit", runStart, "b");
    assertThat(joined(r)).contains("was not produced by branch-review");
    // The disabled path: never written back, resumed only by an explicit accept-divergence.
    assertThat(joined(r)).contains("then commit, push, and run:");
    assertThat(joined(r)).doesNotContain("applied the edits");
    assertThat(fileAt(refValue("refs/heads/run/edit"), "a/file1.txt")).isEqualTo("a content 1\n");
    // Recovery must not pick it up either.
    assertThat(
            plugin
                .getSysInjector()
                .getInstance(
                    io.github.quentinbtd.gerrit.branchreview.recovery.RecoveryService.class)
                .runOnce())
        .isEqualTo(0);
  }

  @Test
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.threads",
      value = "1")
  @com.google.gerrit.acceptance.config.GerritConfig(
      name = "plugin.branch-review.maxQueued",
      value = "1")
  public void recoveryFollowsTheRouteEditSetting() throws Exception {
    setConfig(EDITS_ROUTED);
    ObjectId devStart = pushFrom(devTip, "refs/heads/dev/lost", "a");
    ObjectId mainStart = pushFrom(refValue("refs/heads/master"), "refs/heads/feature/lost", "b");
    int dev = lifecycle("refs/heads/dev/lost").changeNumber;
    int main = lifecycle("refs/heads/feature/lost").changeNumber;
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
      // Both edit events are dropped (queue full).
      editInGerrit(dev, "a/file1.txt", "lost dev edit\n");
      editInGerrit(main, "b/file1.txt", "lost master edit\n");
    } finally {
      release.countDown();
    }
    waitFor(() -> executor.execute(() -> {}));
    var recovery =
        plugin
            .getSysInjector()
            .getInstance(io.github.quentinbtd.gerrit.branchreview.recovery.RecoveryService.class);
    // Only the develop route (on) has recovery work.
    assertThat(recovery.runOnce()).isEqualTo(1);
    waitFor(() -> !refValue("refs/heads/dev/lost").equals(devStart));
    waitFor(
        () ->
            refValue("refs/heads/dev/lost")
                .name()
                .equals(lifecycle("refs/heads/dev/lost").lastSourceId));
    assertThat(fileAt(refValue("refs/heads/dev/lost"), "a/file1.txt")).isEqualTo("lost dev edit\n");
    assertThat(refValue("refs/heads/feature/lost")).isEqualTo(mainStart);
  }

  @Test
  public void reroutingAnActiveBranchBlocksIt() throws Exception {
    pushFrom(devTip, "refs/heads/dev/move", "a");
    int n = lifecycle("refs/heads/dev/move").changeNumber;
    setConfig(
        ROUTED.replace(
            "sourceBranchPattern = refs/heads/run/*",
            "sourceBranchPattern = refs/heads/run/*\n  sourceBranchPattern = refs/heads/dev/move"));
    commits(repo, "b", 1);
    PushResult r = push(repo, "refs/heads/dev/move");
    assertThat(joined(r))
        .contains("configured target changed from refs/heads/develop to refs/heads/runtime");
    assertThat(change(n).branch).isEqualTo("develop");
    assertThat(change(n).revisions).hasSize(1);
    assertThat(lifecycle("refs/heads/dev/move").syncState).isEqualTo(Lifecycle.SyncState.BLOCKED);
  }

  @Test
  public void configWithAMissingTargetIsRejected() throws Exception {
    TestRepository<InMemoryRepository> admin = cloneAs(this.admin);
    admin.git().fetch().setRemote("origin").setRefSpecs("refs/meta/config:refs/meta/config").call();
    admin.reset("refs/meta/config");
    admin
        .branch("HEAD")
        .commit()
        .author(this.admin.newIdent())
        .committer(this.admin.newIdent())
        .message("route to a missing branch")
        .add(
            "branch-review.config",
            ROUTED + "[target \"refs/heads/qa\"]\n  sourceBranchPattern = refs/heads/qa-*\n")
        .create();
    PushResult r = push(admin, "HEAD:refs/meta/config", false);
    assertThat(r.getRemoteUpdate("refs/meta/config").getStatus())
        .isNotEqualTo(RemoteRefUpdate.Status.OK);
    assertThat(r.getMessages()).contains("target branch refs/heads/qa does not exist");
  }
}
