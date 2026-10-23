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
    waitFor(() -> refValue("refs/heads/run/fix") == null);
    waitFor(() -> lifecycle("refs/heads/dev/fix").status == Lifecycle.Status.MERGED);
    // The run route deletes its source branches, the dev route (default false) keeps them.
    assertThat(refValue("refs/heads/dev/fix")).isNotNull();
    assertThat(lifecycle("refs/heads/run/fix").merge.cleanup).isEqualTo("deleted");
    assertThat(lifecycle("refs/heads/dev/fix").merge.cleanup).isEqualTo("none");
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
