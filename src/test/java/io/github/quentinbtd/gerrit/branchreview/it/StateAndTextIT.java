// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.common.RawInputUtil;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.RawInput;
import io.github.quentinbtd.gerrit.branchreview.api.PostSync;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.transport.PushResult;
import org.junit.Before;
import org.junit.Test;

public class StateAndTextIT extends AbstractBranchReviewIT {
  private static final String FEATURE = "refs/heads/feature/text-and-state";
  private TestRepository<InMemoryRepository> repo;
  private int number;

  @Before
  public void createLifecycle() throws Exception {
    enable();
    repo = cloneAs(user);
    commits(repo, "a", 2);
    push(repo, FEATURE);
    number = onlyChange();
  }

  private ChangeInfo pushMore(String prefix) throws Exception {
    commits(repo, prefix, 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    return change(number);
  }

  private String message(ChangeInfo c) {
    return c.revisions.get(c.currentRevision).commit.message;
  }

  @Test
  public void readyStaysReadyAndWipStaysWip() throws Exception {
    gApi.changes().id(project.get(), number).setReadyForReview();
    ChangeInfo c = pushMore("b");
    assertThat(c.workInProgress).isNull();
    assertThat(c.revisions).hasSize(2);
    gApi.changes().id(project.get(), number).setWorkInProgress();
    c = pushMore("c");
    assertThat(c.workInProgress).isTrue();
    assertThat(c.revisions).hasSize(3);
  }

  @Test
  public void reviewersTopicAndHashtagsAreKept() throws Exception {
    gApi.changes().id(project.get(), number).addReviewer(admin.email());
    gApi.changes().id(project.get(), number).topic("my-topic");
    gApi.changes()
        .id(project.get(), number)
        .setHashtags(
            new com.google.gerrit.extensions.api.changes.HashtagsInput(java.util.Set.of("tag")));
    ChangeInfo c = pushMore("b");
    assertThat(c.topic).isEqualTo("my-topic");
    assertThat(c.hashtags).containsExactly("tag");
    assertThat(gApi.changes().id(project.get(), number).reviewers()).isNotEmpty();
  }

  @Test
  public void generatedTextFollowsBranchUntilEdited() throws Exception {
    ChangeInfo c = change(number);
    assertThat(c.subject).isEqualTo("Text and state");
    assertThat(message(c)).contains("Squash of 2 commits");
    c = pushMore("b");
    assertThat(message(c)).contains("Squash of 3 commits");
  }

  @Test
  public void editedSubjectSurvivesAndBodyStillUpdates() throws Exception {
    String msg = message(change(number));
    gApi.changes()
        .id(project.get(), number)
        .setMessage(msg.replace("Text and state", "Human title"));
    ChangeInfo afterEdit = change(number);
    assertThat(afterEdit.revisions).hasSize(2);

    ChangeInfo c = pushMore("b");
    assertThat(c.revisions).hasSize(3);
    assertThat(c.subject).isEqualTo("Human title");
    assertThat(message(c)).contains("Squash of 3 commits");
    Lifecycle lc = lifecycle(FEATURE);
    assertThat(lc.text.subjectOwner.name()).isEqualTo("USER");
    assertThat(lc.text.bodyOwner.name()).isEqualTo("GENERATOR");
  }

  @Test
  public void editedBodyAndTrailersSurviveAndSubjectStillUpdates() throws Exception {
    // Switch to a generated subject that will change: a single commit uses its own subject.
    String msg = message(change(number));
    String edited =
        "Text and state\n\nHand-written description.\n\nBug: 42\nChange-Id: "
            + change(number).changeId
            + "\n";
    gApi.changes().id(project.get(), number).setMessage(edited);
    ChangeInfo c = pushMore("b");
    assertThat(message(c)).contains("Hand-written description.");
    assertThat(message(c)).doesNotContain("Squash of");
    assertThat(message(c)).contains("Bug: 42");
    assertThat(message(c)).endsWith("Change-Id: " + c.changeId + "\n");
    assertThat(msg).isNotEmpty();
  }

  @Test
  public void resetTextRegeneratesWhenExplicitlyRequested() throws Exception {
    String msg = message(change(number));
    gApi.changes()
        .id(project.get(), number)
        .setMessage(msg.replace("Text and state", "Human title"));
    PostSync.Input in = new PostSync.Input();
    in.resetText = true;
    syncViaRest(user, FEATURE, in);
    assertThat(change(number).subject).isEqualTo("Text and state");
  }

  private static final String NO_BACKPORT = DEFAULT_CONFIG + "  syncEditsToBranch = false\n";

  private void editInGerrit(String path, String content) throws Exception {
    var edit = gApi.changes().id(project.get(), number).edit();
    edit.create();
    edit.modifyFile(path, RawInputUtil.create(content));
    edit.publish();
  }

  private org.eclipse.jgit.revwalk.RevCommit sourceTip() throws Exception {
    try (var r = repoManager.openRepository(project);
        var rw = new org.eclipse.jgit.revwalk.RevWalk(r)) {
      return rw.parseCommit(refValue(FEATURE));
    }
  }

  private void syncLocal() throws Exception {
    repo.git().fetch().setRemote("origin").setRefSpecs("+" + FEATURE + ":" + FEATURE).call();
    repo.reset(refValue(FEATURE));
  }

  @Test
  public void humanContentPatchSetPausesSynchronizationWhenBackportDisabled() throws Exception {
    setConfig(NO_BACKPORT);
    editInGerrit("a/file1.txt", "edited in UI\n");
    assertThat(change(number).revisions).hasSize(2);

    commits(repo, "b", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("was not produced by branch-review");
    assertThat(joined(r)).contains("git diff ");
    assertThat(joined(r)).contains("| git apply --3way");
    assertThat(joined(r)).contains("accept_divergence");
    assertThat(joined(r)).doesNotContain("<host>");
    assertThat(change(number).revisions).hasSize(2);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.DIVERGED);

    PostSync.Input in = new PostSync.Input();
    in.acceptDivergence = true;
    syncViaRest(user, FEATURE, in);
    assertThat(change(number).revisions).hasSize(3);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.SYNCED);
  }

  @Test
  public void uiEditIsAppliedToTheBranch() throws Exception {
    var before = sourceTip();
    editInGerrit("a/file1.txt", "edited in UI\n");
    waitFor(() -> !refValue(FEATURE).equals(before));
    waitFor(() -> lifecycle(FEATURE).syncState == Lifecycle.SyncState.SYNCED);

    var tip = sourceTip();
    assertThat(tip.getParentCount()).isEqualTo(1);
    assertThat(tip.getParent(0)).isEqualTo(before);
    assertThat(tip.getAuthorIdent().getEmailAddress()).isEqualTo(admin.email());
    assertThat(tip.getShortMessage())
        .isEqualTo("Apply review edits from Change " + number + " patch set 2");
    assertThat(fileAt(tip, "a/file1.txt")).isEqualTo("edited in UI\n");
    // Only the edit is carried over: the branch never receives target changes.
    assertThat(between(before, tip)).hasSize(1);
    Lifecycle lc = lifecycle(FEATURE);
    assertThat(lc.lastSourceId).isEqualTo(tip.name());
    assertThat(lc.lastSyntheticPatchSet).isEqualTo(2);
    assertThat(change(number).revisions).hasSize(2);

    // A push based on the old tip is an ordinary non-fast-forward; after pulling it works.
    commits(repo, "b", 1);
    PushResult r = push(repo, "HEAD:" + FEATURE, false);
    assertThat(r.getRemoteUpdate(FEATURE).getStatus())
        .isNotEqualTo(org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK);
    syncLocal();
    commits(repo, "c", 1);
    r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    ChangeInfo c = change(number);
    assertThat(c.revisions).hasSize(3);
    var rev = org.eclipse.jgit.lib.ObjectId.fromString(c.currentRevision);
    assertThat(fileAt(rev, "a/file1.txt")).isEqualTo("edited in UI\n");
    assertThat(fileAt(rev, "c/file1.txt")).isEqualTo("c content 1\n");
  }

  @Test
  public void editIsAppliedOnTopOfAConcurrentPush() throws Exception {
    // The edit is published while nothing reacts (e.g. the developer pushes right after).
    setConfig(NO_BACKPORT);
    editInGerrit("a/file1.txt", "edited in UI\n");
    enable();
    var pushed = commits(repo, "b", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("applied the edits of patch set 2");
    assertThat(joined(r)).contains("run git pull before pushing again");
    var tip = sourceTip();
    assertThat(tip.getParent(0)).isEqualTo(pushed);
    ChangeInfo c = change(number);
    assertThat(c.revisions).hasSize(3);
    var rev = org.eclipse.jgit.lib.ObjectId.fromString(c.currentRevision);
    assertThat(fileAt(rev, "a/file1.txt")).isEqualTo("edited in UI\n");
    assertThat(fileAt(rev, "b/file1.txt")).isEqualTo("b content 1\n");
    assertThat(lifecycle(FEATURE).lastSourceId).isEqualTo(tip.name());
  }

  @Test
  public void editAlreadyInTheBranchIsRecognized() throws Exception {
    setConfig(NO_BACKPORT);
    editInGerrit("a/file1.txt", "edited in UI\n");
    enable();
    var pushed =
        repo.branch("HEAD")
            .commit()
            .author(user.newIdent())
            .committer(user.newIdent())
            .message("take the review edit")
            .add("a/file1.txt", "edited in UI\n")
            .create();
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r)).doesNotContain("applied the edits");
    assertThat(refValue(FEATURE)).isEqualTo(pushed);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.SYNCED);
    assertThat(lifecycle(FEATURE).lastSourceId).isEqualTo(pushed.name());
  }

  @Test
  public void conflictingEditPausesWithExplanation() throws Exception {
    setConfig(NO_BACKPORT);
    editInGerrit("a/file1.txt", "edited in UI\n");
    enable();
    repo.branch("HEAD")
        .commit()
        .author(user.newIdent())
        .committer(user.newIdent())
        .message("conflicting local change")
        .add("a/file1.txt", "edited locally\n")
        .create();
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("the edits could not be applied to the branch");
    assertThat(joined(r)).contains("conflict with the branch in a/file1.txt");
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.DIVERGED);
    assertThat(change(number).revisions).hasSize(2);
  }

  private org.eclipse.jgit.revwalk.RevCommit amendTipWith(String path, String content)
      throws Exception {
    var tip = repo.getRevWalk().parseCommit(repo.getRepository().resolve("HEAD"));
    var amended =
        repo.amend(tip)
            .author(user.newIdent())
            .committer(user.newIdent())
            .add(path, content)
            .create();
    repo.reset(amended);
    return amended;
  }

  @Test
  public void forcePushReplacesConflictingEdit() throws Exception {
    setConfig(NO_BACKPORT);
    editInGerrit("a/file1.txt", "edited in UI\n");
    enable();
    var forced = amendTipWith("a/file1.txt", "rewritten locally\n");
    PushResult r = push(repo, "HEAD:" + FEATURE, true);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("history was rewritten (force push)");
    assertThat(joined(r)).contains("they remain visible in patch set 2");
    assertThat(joined(r)).contains("updated Change " + number + " with patch set 3");
    assertThat(refValue(FEATURE)).isEqualTo(forced);
    var rev = org.eclipse.jgit.lib.ObjectId.fromString(change(number).currentRevision);
    assertThat(fileAt(rev, "a/file1.txt")).isEqualTo("rewritten locally\n");
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.SYNCED);
  }

  @Test
  public void forcePushKeepsNonConflictingEdit() throws Exception {
    setConfig(NO_BACKPORT);
    editInGerrit("a/file1.txt", "edited in UI\n");
    enable();
    var forced = amendTipWith("x/new.txt", "new\n");
    PushResult r = push(repo, "HEAD:" + FEATURE, true);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("applied the edits of patch set 2");
    assertThat(sourceTip().getParent(0)).isEqualTo(forced);
    var rev = org.eclipse.jgit.lib.ObjectId.fromString(change(number).currentRevision);
    assertThat(fileAt(rev, "a/file1.txt")).isEqualTo("edited in UI\n");
    assertThat(fileAt(rev, "x/new.txt")).isEqualTo("new\n");
  }

  @Test
  public void forcePushOverridesWhenBackportDisabled() throws Exception {
    setConfig(NO_BACKPORT);
    editInGerrit("a/file1.txt", "edited in UI\n");
    var forced = amendTipWith("x/new.txt", "new\n");
    PushResult r = push(repo, "HEAD:" + FEATURE, true);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("history was rewritten (force push)");
    assertThat(refValue(FEATURE)).isEqualTo(forced);
    var rev = org.eclipse.jgit.lib.ObjectId.fromString(change(number).currentRevision);
    assertThat(fileAt(rev, "a/file1.txt")).isEqualTo("a content 1\n");
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.SYNCED);
  }

  @Test
  public void editorWithoutBranchPushRightKeepsThePause() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block(
                    com.google.gerrit.entities.Permission.PUSH)
                .ref(FEATURE)
                .group(adminGroupUuid()))
        .update();
    var before = refValue(FEATURE);
    editInGerrit("a/file1.txt", "edited in UI\n");
    waitFor(() -> lifecycle(FEATURE).syncState == Lifecycle.SyncState.DIVERGED);
    assertThat(refValue(FEATURE)).isEqualTo(before);
  }

  @Test
  public void unpublishedChangeEditIsNotTouched() throws Exception {
    var edit = gApi.changes().id(project.get(), number).edit();
    edit.create();
    edit.modifyFile("a/file1.txt", RawInputUtil.create("draft\n"));
    pushMore("b");
    assertThat(change(number).revisions).hasSize(2);
    assertThat(gApi.changes().id(project.get(), number).edit().get()).isPresent();
  }

  @Test
  public void guiRebaseIsRecognizedAsDerivative() throws Exception {
    serverCommit("refs/heads/master", "other.txt", "other\n");
    gApi.changes().id(project.get(), number).current().rebase();
    assertThat(change(number).revisions).hasSize(2);
    ChangeInfo c = pushMore("b");
    assertThat(c.revisions).hasSize(3);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.SYNCED);
  }

  @Test
  public void statusEndpointReportsLifecycle() throws Exception {
    RestResponse r = status(user, FEATURE);
    r.assertOK();
    String body = r.getEntityContent();
    assertThat(body).contains("\"change_number\":" + number);
    assertThat(body).contains("\"in_sync\":true");
    assertThat(body).contains("\"sync_state\":\"SYNCED\"");
    status(user, "refs/heads/does-not-matter").assertOK();
    status(user, "refs/tags/v1").assertBadRequest();
  }

  @SuppressWarnings("unused")
  private static RawInput unused() {
    return null;
  }
}
