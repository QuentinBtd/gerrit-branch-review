// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.extensions.client.ChangeStatus;
import io.github.quentinbtd.gerrit.branchreview.api.PostSync;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.junit.Before;
import org.junit.Test;

public class LifecycleIT extends AbstractBranchReviewIT {
  private static final String FEATURE = "refs/heads/feature/life";
  private TestRepository<InMemoryRepository> repo;
  private int number;

  @Before
  public void createLifecycle() throws Exception {
    enable();
    repo = cloneAs(user);
    commits(repo, "a", 1);
    push(repo, FEATURE);
    number = onlyChange();
  }

  @Test
  public void abandonedChangeIsNotRestoredAutomatically() throws Exception {
    gApi.changes().id(project.get(), number).abandon();
    commits(repo, "b", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("is abandoned and is not restored automatically");
    assertThat(change(number).status).isEqualTo(ChangeStatus.ABANDONED);
    assertThat(change(number).revisions).hasSize(1);

    // Explicit restore, then the next sync updates the same Change.
    gApi.changes().id(project.get(), number).restore();
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertThat(change(number).revisions).hasSize(2);
  }

  @Test
  public void newLifecycleAfterAbandon() throws Exception {
    gApi.changes().id(project.get(), number).abandon();
    PostSync.Input in = new PostSync.Input();
    in.newLifecycle = true;
    syncViaRest(user, FEATURE, in);
    assertThat(changes()).hasSize(2);
    Lifecycle lc = lifecycle(FEATURE);
    assertThat(lc.changeNumber).isNotEqualTo(number);
    assertThat(lc.previousChangeNumber).isEqualTo(number);
    assertThat(lc.changeKey).isNotEqualTo(change(number).changeId);
  }

  @Test
  public void newLifecycleRefusedWhileChangeOpen() throws Exception {
    PostSync.Input in = new PostSync.Input();
    in.newLifecycle = true;
    String out = syncViaRest(user, FEATURE, in);
    assertThat(out).contains("is still open; abandon it before starting a new lifecycle");
    assertThat(changes()).hasSize(1);
  }

  @Test
  public void deletedSourceKeepsChangeAndRecreationIsExplicit() throws Exception {
    PushResult r = push(repo, ":" + FEATURE, true);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("source branch deleted");
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.SOURCE_DELETED);
    assertThat(change(number).status).isEqualTo(ChangeStatus.NEW);

    // Re-creating the same name is not silently treated as the old branch.
    repo.reset(refValue("refs/heads/master"));
    commits(repo, "again", 1);
    r = push(repo, FEATURE);
    assertThat(joined(r)).contains("was deleted and recreated");
    assertThat(change(number).revisions).hasSize(1);

    PostSync.Input in = new PostSync.Input();
    in.resume = true;
    syncViaRest(user, FEATURE, in);
    assertThat(change(number).revisions).hasSize(2);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.SYNCED);
  }

  @Test
  public void renameIsDeletionPlusCreation() throws Exception {
    RevCommit tip = repo.getRevWalk().parseCommit(refValue(FEATURE));
    push(repo, ":" + FEATURE, true);
    PushResult r = push(repo, tip.name() + ":refs/heads/feature/renamed", false);
    assertThat(joined(r)).contains("refs/heads/feature/renamed: created Change");
    assertThat(changes()).hasSize(2);
  }

  @Test
  public void targetChangeDuringActiveLifecycleIsRefused() throws Exception {
    serverCommit("refs/heads/stable", "s.txt", "s\n");
    setConfig(
        DEFAULT_CONFIG.replace(
            "targetBranch = refs/heads/master", "targetBranch = refs/heads/stable"));
    commits(repo, "b", 1);
    PushResult r = push(repo, FEATURE);
    assertThat(joined(r))
        .contains("configured target changed from refs/heads/master to refs/heads/stable");
    assertThat(change(number).branch).isEqualTo("master");
    assertThat(change(number).revisions).hasSize(1);
    assertThat(status(user, FEATURE).getEntityContent())
        .contains("differs from the lifecycle target");
  }

  @Test
  public void patternChangeDoesNotMoveOrDuplicate() throws Exception {
    setConfig(DEFAULT_CONFIG.replace("sourceBranchPattern = refs/heads/feature/*\n", ""));
    commits(repo, "b", 1);
    PushResult r = push(repo, FEATURE);
    assertThat(joined(r)).contains("no longer matches branch-review.config patterns");
    assertThat(changes()).hasSize(1);
    assertThat(change(number).revisions).hasSize(1);
  }

  @Test
  public void forcePushRebaseAndTargetMergedIntoSource() throws Exception {
    serverCommit("refs/heads/master", "t.txt", "target\n");
    repo.git()
        .fetch()
        .setRemote("origin")
        .setRefSpecs("+refs/heads/master:refs/remotes/origin/master")
        .call();
    ObjectId master = refValue("refs/heads/master");

    // Merge the target into the source.
    RevCommit merge =
        repo.branch("HEAD")
            .commit()
            .parent(repo.getRevWalk().parseCommit(master))
            .author(user.newIdent())
            .committer(user.newIdent())
            .message("Merge master")
            .add("t.txt", "target\n")
            .create();
    push(repo, FEATURE);
    var c = change(number);
    assertThat(c.revisions).hasSize(2);
    ObjectId rev = ObjectId.fromString(c.currentRevision);
    assertThat(between(master, rev)).hasSize(1);
    assertThat(fileAt(rev, "a/file1.txt")).isEqualTo("a content 1\n");
    assertThat(fileAt(rev, "t.txt")).isEqualTo("target\n");
    assertThat(merge).isNotNull();

    // Rewrite history (rebase onto the target) and force push.
    repo.reset(master);
    repo.branch("HEAD")
        .commit()
        .author(user.newIdent())
        .committer(user.newIdent())
        .message("rebased")
        .add("a/file1.txt", "rebased content\n")
        .create();
    PushResult r = push(repo, "HEAD:" + FEATURE, true);
    assertOk(r, FEATURE);
    c = change(number);
    assertThat(c.revisions).hasSize(3);
    rev = ObjectId.fromString(c.currentRevision);
    assertThat(fileAt(rev, "a/file1.txt")).isEqualTo("rebased content\n");
    assertThat(fileAt(rev, "t.txt")).isEqualTo("target\n");
  }

  @Test
  public void conflictIsReportedAndBlocksSubmit() throws Exception {
    serverCommit("refs/heads/master", "a/file1.txt", "target edit\n");
    repo.branch("HEAD")
        .commit()
        .author(user.newIdent())
        .committer(user.newIdent())
        .message("conflicting")
        .add("a/file1.txt", "source edit\n")
        .create();
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("conflicts with refs/heads/master in: a/file1.txt");
    assertThat(change(number).revisions).hasSize(1);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.CONFLICT);
    gApi.changes().id(project.get(), number).setReadyForReview();
    gApi.changes()
        .id(project.get(), number)
        .current()
        .review(com.google.gerrit.extensions.api.changes.ReviewInput.approve());
    com.google.gerrit.testing.GerritJUnit.assertThrows(
        com.google.gerrit.extensions.restapi.ResourceConflictException.class,
        () -> gApi.changes().id(project.get(), number).current().submit());
  }

  @Test
  public void unrelatedHistoryIsRejected() throws Exception {
    RevCommit orphan =
        repo.commit()
            .author(user.newIdent())
            .committer(user.newIdent())
            .message("orphan")
            .add("o.txt", "o")
            .create();
    PushResult r = push(repo, orphan.name() + ":refs/heads/feature/orphan", false);
    assertThat(joined(r)).contains("shares no history with refs/heads/master");
    assertThat(changes()).hasSize(1);
  }
}
