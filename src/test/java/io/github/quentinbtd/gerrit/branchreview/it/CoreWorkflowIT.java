// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import io.github.quentinbtd.gerrit.branchreview.api.PostSync;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import java.util.List;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Test;

public class CoreWorkflowIT extends AbstractBranchReviewIT {
  private static final String FEATURE = "refs/heads/feature/my-feature";

  @Test
  public void disabledWithoutConfigFile() throws Exception {
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 2);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(pluginLines(r)).isEmpty();
    assertThat(changes()).isEmpty();
    assertThat(lifecycle(FEATURE)).isNull();
  }

  @Test
  public void explicitlyDisabledAndParentConfigDoNotEnable() throws Exception {
    setConfig(DEFAULT_CONFIG.replace("enabled = true", "enabled = false"));
    // A parent project with an enabled file must not enable the child: no inheritance.
    try (var repo = repoManager.openRepository(allProjects);
        var tr = new org.eclipse.jgit.junit.TestRepository<>(repo)) {
      tr.branch("refs/meta/config").commit().add("branch-review.config", DEFAULT_CONFIG).create();
    }
    projectCache.evictAndReindex(allProjects);
    projectCache.evictAndReindex(project);
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(pluginLines(r)).isEmpty();
    assertThat(changes()).isEmpty();
  }

  @Test
  public void pushCreatesOneWipChangeAndUpdatesIt() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit tip = commits(repo, "a", 3);

    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(refValue(FEATURE)).isEqualTo(tip);
    List<ChangeInfo> cs = changes();
    assertThat(cs).hasSize(1);
    ChangeInfo c = cs.get(0);
    assertThat(c.workInProgress).isTrue();
    assertThat(c.branch).isEqualTo("master");
    assertThat(c.owner._accountId).isEqualTo(user.id().get());
    CommitInfo commit = c.revisions.get(c.currentRevision).commit;
    assertThat(commit.parents).hasSize(1);
    assertThat(commit.parents.get(0).commit).isEqualTo(refValue("refs/heads/master").name());
    assertThat(commit.subject).isEqualTo("My feature");
    assertThat(pluginLines(r))
        .containsExactly(
            FEATURE + ": created Change " + c._number + " (WIP)",
            canonicalWebUrl.get() + "c/" + project.get() + "/+/" + c._number)
        .inOrder();
    // The source branch history is untouched and its commits are not part of the review commit.
    assertThat(between(refValue("refs/heads/master"), refValue(FEATURE))).hasSize(3);

    Lifecycle lc = lifecycle(FEATURE);
    assertThat(lc.changeNumber).isEqualTo(c._number);
    assertThat(lc.lastSourceId).isEqualTo(tip.name());
    assertThat(lc.syncState).isEqualTo(Lifecycle.SyncState.SYNCED);
    assertThat(lc.changeKey).isEqualTo(c.changeId);

    RevCommit tip2 = commits(repo, "b", 1);
    r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(changes()).hasSize(1);
    ChangeInfo c2 = change(c._number);
    assertThat(c2.revisions).hasSize(2);
    assertThat(c2.workInProgress).isTrue();
    assertThat(c2.changeId).isEqualTo(c.changeId);
    assertThat(pluginLines(r))
        .containsExactly(
            FEATURE + ": updated Change " + c._number + " with patch set 2 (WIP)",
            canonicalWebUrl.get() + "c/" + project.get() + "/+/" + c._number)
        .inOrder();
    assertThat(lifecycle(FEATURE).lastSourceId).isEqualTo(tip2.name());
    String rev = c2.currentRevision;
    assertThat(fileAt(org.eclipse.jgit.lib.ObjectId.fromString(rev), "a/file3.txt"))
        .isEqualTo("a content 3\n");
    assertThat(fileAt(org.eclipse.jgit.lib.ObjectId.fromString(rev), "b/file1.txt"))
        .isEqualTo("b content 1\n");
  }

  @Test
  public void identicalRetryAndEquivalentContentAddNoPatchSet() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit tip = commits(repo, "a", 2);
    push(repo, FEATURE);
    int n = onlyChange();

    // Explicit retry of the same state.
    var out = syncViaRest(user, FEATURE, new PostSync.Input());
    assertThat(out).contains("\"result\":\"noop\"");
    assertThat(change(n).revisions).hasSize(1);

    // A different source commit with the same net content and base: no new patch set.
    repo.reset(tip);
    RevCommit amended =
        repo.amend(tip)
            .message("reworded but same tree")
            .author(user.newIdent())
            .committer(user.newIdent())
            .create();
    repo.reset(amended);
    PushResult r = push(repo, "HEAD:" + FEATURE, true);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("is up to date (patch set 1");
    assertThat(change(n).revisions).hasSize(1);
    assertThat(lifecycle(FEATURE).lastSourceId).isEqualTo(amended.name());
  }

  @Test
  public void sourceChangeIdCannotHijackAnotherChange() throws Exception {
    enable();
    // An unrelated ordinary review.
    var unrelated = createChange();
    String foreignId = unrelated.getChangeId();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    repo.reset(refValue("refs/heads/master"));
    repo.branch("HEAD")
        .commit()
        .message("steal\n\nChange-Id: " + foreignId + "\n")
        .author(user.newIdent())
        .committer(user.newIdent())
        .add("x.txt", "x")
        .create();
    commits(repo, "a", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(gApi.changes().id(unrelated.getChangeId()).get().revisions).hasSize(1);
    Lifecycle lc = lifecycle(FEATURE);
    assertThat(lc.changeKey).isNotEqualTo(foreignId);
    assertThat(change(lc.changeNumber).changeId).isEqualTo(lc.changeKey);
  }

  @Test
  public void refFiltering() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(admin);
    commits(repo, "a", 1);
    for (String ref : List.of("refs/heads/feature/private/x", "refs/heads/other", "refs/tags/v1")) {
      PushResult r = push(repo, "HEAD:" + ref, false);
      assertOk(r, ref);
      assertThat(pluginLines(r)).isEmpty();
    }
    assertThat(changes()).isEmpty();
    PushResult r = push(repo, "refs/heads/bugfix/1");
    assertThat(joined(r)).contains("refs/heads/bugfix/1: created Change");
  }

  @Test
  public void targetPushIsIgnored() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(admin);
    commits(repo, "a", 1);
    PushResult r = push(repo, "refs/heads/master");
    assertOk(r, "refs/heads/master");
    assertThat(pluginLines(r)).isEmpty();
    assertThat(changes()).isEmpty();
  }

  @Test
  public void multiRefPushReportsEachRef() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit a = commits(repo, "a", 1);
    repo.reset(refValue("refs/heads/master"));
    RevCommit b = commits(repo, "b", 1);
    PushResult r =
        repo.git()
            .push()
            .setRemote("origin")
            .setRefSpecs(
                new RefSpec(a.name() + ":refs/heads/feature/one"),
                new RefSpec(b.name() + ":refs/heads/feature/two"))
            .call()
            .iterator()
            .next();
    assertThat(changes()).hasSize(2);
    String out = joined(r);
    assertThat(out).contains("refs/heads/feature/one: created Change");
    assertThat(out).contains("refs/heads/feature/two: created Change");
    assertThat(lifecycle("refs/heads/feature/one").changeNumber)
        .isNotEqualTo(lifecycle("refs/heads/feature/two").changeNumber);
  }

  @Test
  public void onlyAcceptedCommandsAreSynchronized() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit base = repo.getRevWalk().parseCommit(refValue("refs/heads/master"));
    commits(repo, "a", 1);
    push(repo, "refs/heads/bugfix/1");
    // A non-fast-forward to bugfix/1 is rejected (no force there), feature/ok is accepted.
    repo.reset(base);
    RevCommit other = commits(repo, "b", 1);
    PushResult r =
        repo.git()
            .push()
            .setRemote("origin")
            .setRefSpecs(
                new RefSpec(other.name() + ":refs/heads/bugfix/1"),
                new RefSpec(other.name() + ":refs/heads/feature/ok"))
            .call()
            .iterator()
            .next();
    assertThat(r.getRemoteUpdate("refs/heads/bugfix/1").getStatus())
        .isNotEqualTo(org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK);
    assertOk(r, "refs/heads/feature/ok");
    assertThat(joined(r)).contains("refs/heads/feature/ok: created Change");
    assertThat(joined(r)).doesNotContain("refs/heads/bugfix/1:");
    assertThat(change(lifecycle("refs/heads/bugfix/1").changeNumber).revisions).hasSize(1);
  }

  @Test
  public void rejectedAtomicPushSynchronizesNothing() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit base = repo.getRevWalk().parseCommit(refValue("refs/heads/master"));
    commits(repo, "a", 1);
    push(repo, "refs/heads/bugfix/1");
    repo.reset(base);
    RevCommit other = commits(repo, "b", 1);
    PushResult r =
        repo.git()
            .push()
            .setRemote("origin")
            .setAtomic(true)
            .setRefSpecs(
                new RefSpec(other.name() + ":refs/heads/bugfix/1"),
                new RefSpec(other.name() + ":refs/heads/feature/atomic"))
            .call()
            .iterator()
            .next();
    assertThat(r.getRemoteUpdate("refs/heads/feature/atomic").getStatus())
        .isNotEqualTo(org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK);
    assertThat(pluginLines(r)).isEmpty();
    assertThat(refValue("refs/heads/feature/atomic")).isNull();
    assertThat(lifecycle("refs/heads/feature/atomic")).isNull();
  }

  @Test
  public void emptyBranchCreatesNoChange() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    repo.reset(refValue("refs/heads/master"));
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r))
        .contains("has no changes relative to refs/heads/master; no Change was created");
    assertThat(changes()).isEmpty();
  }

  @Test
  public void contributionThatBecomesEmptyIsNotMisrepresented() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit master = repo.getRevWalk().parseCommit(refValue("refs/heads/master"));
    commits(repo, "a", 1);
    push(repo, FEATURE);
    int n = onlyChange();
    repo.reset(master);
    PushResult r = push(repo, "HEAD:" + FEATURE, true);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("NOT synchronized");
    assertThat(joined(r)).contains("no longer represents this branch");
    assertThat(change(n).revisions).hasSize(1);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.EMPTY);
  }

  @Test
  public void invalidConfigIsReportedAndNothingIsMutated() throws Exception {
    setConfig("[branchReview]\n  enabled = true\n  targetBranch = master\n");
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r))
        .contains(
            "branch-review.config is invalid; branch updates were accepted but NOT synchronized");
    assertThat(changes()).isEmpty();
    assertThat(lifecycle(FEATURE)).isNull();
  }

  @Test
  public void configCacheFollowsUpdates() throws Exception {
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    push(repo, FEATURE);
    assertThat(changes()).isEmpty();
    enable();
    commits(repo, "b", 1);
    PushResult r = push(repo, FEATURE);
    assertThat(joined(r)).contains("created Change");
    setConfig(null);
    commits(repo, "c", 1);
    r = push(repo, FEATURE);
    assertThat(pluginLines(r)).isEmpty();
    // Disabling neither abandons the Change nor deletes the branch.
    assertThat(change(onlyChange()).status)
        .isEqualTo(com.google.gerrit.extensions.client.ChangeStatus.NEW);
    assertThat(refValue(FEATURE)).isNotNull();
  }
}
