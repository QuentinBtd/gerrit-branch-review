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

  @Test
  public void humanContentPatchSetPausesSynchronization() throws Exception {
    var edit = gApi.changes().id(project.get(), number).edit();
    edit.create();
    edit.modifyFile("a/file1.txt", RawInputUtil.create("edited in UI\n"));
    edit.publish();
    assertThat(change(number).revisions).hasSize(2);

    commits(repo, "b", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("was not produced by branch-review");
    assertThat(change(number).revisions).hasSize(2);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.DIVERGED);

    PostSync.Input in = new PostSync.Input();
    in.acceptDivergence = true;
    syncViaRest(user, FEATURE, in);
    assertThat(change(number).revisions).hasSize(3);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.SYNCED);
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
