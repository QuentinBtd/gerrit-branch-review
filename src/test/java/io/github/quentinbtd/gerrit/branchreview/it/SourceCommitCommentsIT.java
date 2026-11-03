// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.ExtensionRegistry;
import com.google.gerrit.extensions.api.changes.HashtagsInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.events.CommentAddedListener;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.inject.Inject;
import io.github.quentinbtd.gerrit.branchreview.api.PostSync;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

public class SourceCommitCommentsIT extends AbstractBranchReviewIT {
  private static final String FEATURE = "refs/heads/feature/messages";

  @Inject private ExtensionRegistry extensionRegistry;

  @Test
  public void readerMissingHeadTagCannotBlockSynchronization() throws Exception {
    forgedTagCannotAffectSynchronization(false, false);
  }

  @Test
  public void readerFutureHeadTagCannotSuppressSourceMessages() throws Exception {
    forgedTagCannotAffectSynchronization(false, true);
  }

  @Test
  public void sameAuthorMissingHeadTagCannotBlockSynchronization() throws Exception {
    forgedTagCannotAffectSynchronization(true, false);
  }

  @Test
  public void sameAuthorFutureHeadTagCannotSuppressSourceMessages() throws Exception {
    forgedTagCannotAffectSynchronization(true, true);
  }

  private void forgedTagCannotAffectSynchronization(boolean sameAuthor, boolean future)
      throws Exception {
    enable();
    var repo = cloneAs(user);
    var a = commit(repo, "A", "initial genuine body");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    var b = commit(repo, "B", "must not be suppressed by forged watermark");
    // Make the future source object available without synchronizing this lifecycle.
    String staged = "refs/heads/feature/private/staged";
    assertOk(push(repo, staged), staged);
    var attacker =
        sameAuthor ? user : accountCreator.create("reader", "reader@example.org", "Reader", null);
    if (!sameAuthor) {
      var group = gApi.groups().create("readers");
      group.addMembers(attacker.username());
      var uuid = com.google.gerrit.entities.AccountGroup.uuid(group.get().id);
      projectOperations
          .project(project)
          .forUpdate()
          .add(
              com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block(
                      com.google.gerrit.entities.Permission.ADD_PATCH_SET)
                  .ref("refs/for/refs/heads/master")
                  .group(uuid))
          .add(
              com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block(
                      com.google.gerrit.entities.Permission.PUSH)
                  .ref("refs/heads/feature/*")
                  .group(uuid))
          .update();
      assertThat(
              forgeryPermissions
                  .absentUser(attacker.id())
                  .change(
                      forgeryNotes.createChecked(project, com.google.gerrit.entities.Change.id(n)))
                  .test(com.google.gerrit.server.permissions.ChangePermission.ADD_PATCH_SET))
          .isFalse();
    }
    String genuineTag = comments(n).get(0).tag;
    String head = future ? b.name() : "1111111111111111111111111111111111111111";
    var input = new ReviewInput();
    input.message = "ordinary discussion with attacker-controlled tag";
    input.tag = genuineTag.substring(0, genuineTag.lastIndexOf(':') + 1) + head;
    requestScopeOperations.setApiUser(attacker.id());
    gApi.changes().id(project.get(), n).current().review(input);
    assertThat(
            gApi.changes().id(project.get(), n).messages().stream()
                .filter(
                    m -> input.tag.equals(m.tag) && m.author._accountId.equals(attacker.id().get()))
                .count())
        .isEqualTo(1L);
    requestScopeOperations.setApiUser(admin.id());
    var result = push(repo, FEATURE);
    assertOk(result, FEATURE);
    assertThat(joined(result)).doesNotContain("NOT synchronized");
    assertThat(change(n).revisions).hasSize(2);
    assertThat(comments(n)).hasSize(2);
    assertThat(comments(n).get(1).message).contains("must not be suppressed by forged watermark");
    assertThat(lifecycle(FEATURE).lastSourceId).isEqualTo(b.name());
    // Retry from stale lifecycle state must reconcile only genuine publication evidence.
    try (var r = repoManager.openRepository(project)) {
      var loaded = stateStore.loadLifecycle(r, FEATURE);
      loaded.value().lastSourceId = a.name();
      loaded.value().syncState = Lifecycle.SyncState.FAILED;
      stateStore.saveLifecycle(
          r, project, loaded.value(), loaded.refId(), "simulate completion failure");
    }
    syncViaRest(user, FEATURE, new PostSync.Input());
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertThat(comments(n)).hasSize(2);
    assertThat(lifecycle(FEATURE).lastSourceId).isEqualTo(b.name());
  }

  @Inject
  private com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations
      requestScopeOperations;

  @Inject private com.google.gerrit.server.permissions.PermissionBackend forgeryPermissions;
  @Inject private com.google.gerrit.server.notedb.ChangeNotes.Factory forgeryNotes;

  @Test
  public void publicationRefAndNoteDbMessageShareTheProjectBatch() throws Exception {
    enable();
    var repo = cloneAs(user);
    var batches = new java.util.concurrent.CopyOnWriteArrayList<Set<String>>();
    var listener =
        new com.google.gerrit.extensions.events.GitBatchRefUpdateListener() {
          @Override
          public void onGitBatchRefUpdate(Event event) {
            batches.add(Set.copyOf(event.getRefNames()));
          }
        };
    try (var registration = extensionRegistry.newRegistration().add(listener)) {
      var a = commit(repo, "A", "atomic initial publication");
      assertOk(push(repo, FEATURE), FEATURE);
      assertThat(lifecycle(FEATURE).lastError).isNull();
      int n = onlyChange();
      assertPublicationBatch(batches, n, a);
      batches.clear();
      var b = commit(repo, "B", "atomic patch set publication");
      assertOk(push(repo, FEATURE), FEATURE);
      assertPublicationBatch(batches, n, b);
      batches.clear();
      var amended =
          repo.amend(b)
              .author(user.newIdent())
              .committer(user.newIdent())
              .message("Reworded B\n\natomic comment-only publication")
              .create();
      repo.reset(amended);
      assertOk(push(repo, "HEAD:" + FEATURE, true), FEATURE);
      assertPublicationBatch(batches, n, amended);
      assertThat(change(n).revisions).hasSize(2);
      assertThat(comments(n)).hasSize(3);
      assertThat(publicationRefs(n)).hasSize(3);
    }
  }

  private void assertPublicationBatch(List<Set<String>> batches, int number, RevCommit source)
      throws Exception {
    String publication = publicationPrefix(number) + source.name();
    String meta =
        com.google.gerrit.entities.RefNames.changeMetaRef(
            com.google.gerrit.entities.Change.id(number));
    assertThat(batches.stream().anyMatch(b -> b.contains(publication) && b.contains(meta)))
        .isTrue();
    assertThat(refValue(publication)).isEqualTo(source);
  }

  @Test
  public void userCannotCreateReplaceOrDeletePublicationEvidence() throws Exception {
    enable();
    var repo = cloneAs(user);
    var a = commit(repo, "A", "protected evidence");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    String evidence = publicationPrefix(n) + a.name();
    assertThat(refValue(evidence)).isEqualTo(a);
    var b = commit(repo, "B", "unpublished source");
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow(
                    com.google.gerrit.entities.Permission.PUSH)
                .ref("refs/meta/*")
                .group(com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS)
                .force(true))
        .add(
            com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow(
                    com.google.gerrit.entities.Permission.CREATE)
                .ref("refs/meta/*")
                .group(com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS))
        .add(
            com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow(
                    com.google.gerrit.entities.Permission.DELETE)
                .ref("refs/meta/*")
                .group(com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS))
        .update();
    for (String spec :
        List.of("HEAD:" + evidence, ":" + evidence, "HEAD:" + publicationPrefix(n) + b.name())) {
      for (var update : push(repo, spec, true).getRemoteUpdates()) {
        assertThat(update.getStatus())
            .isNotEqualTo(org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK);
      }
    }
    assertThat(refValue(evidence)).isEqualTo(a);
    assertThat(refValue(publicationPrefix(n) + b.name())).isNull();
    assertThat(comments(n)).hasSize(1);
  }

  private String publicationPrefix(int number) throws Exception {
    return "refs/meta/branch-review/publications/" + number + "/" + lifecycle(FEATURE).id + "/";
  }

  private List<org.eclipse.jgit.lib.Ref> publicationRefs(int number) throws Exception {
    try (var repo = repoManager.openRepository(project)) {
      return repo.getRefDatabase().getRefsByPrefix(publicationPrefix(number));
    }
  }

  @Test
  public void recoversInsertedChangeAndCommentTogether() throws Exception {
    enable();
    var repo = cloneAs(user);
    var a = commit(repo, "A", "retained after crash");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    try (var r = repoManager.openRepository(project)) {
      var loaded = stateStore.loadLifecycle(r, FEATURE);
      var lc = loaded.value();
      lc.pending = new Lifecycle.Pending();
      lc.pending.op = "create";
      lc.pending.changeNumber = n;
      lc.pending.patchSet = 1;
      lc.pending.commit = lc.lastSyntheticCommit;
      lc.pending.sourceId = lc.lastSourceId;
      lc.pending.targetId = lc.lastTargetId;
      lc.changeNumber = 0;
      lc.lastSyntheticPatchSet = 0;
      lc.lastSourceId = null;
      lc.syncState = Lifecycle.SyncState.PENDING;
      stateStore.saveLifecycle(r, project, lc, loaded.refId(), "simulate crash after insertion");
    }
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertThat(change(n).revisions).hasSize(1);
    assertThat(comments(n)).hasSize(1);
    assertThat(comments(n).get(0).message).contains("retained after crash");
    assertThat(lifecycle(FEATURE).lastSourceId).isEqualTo(a.name());
    commit(repo, "B", "only new after recovery");
    assertOk(push(repo, FEATURE), FEATURE);
    assertThat(comments(n)).hasSize(2);
    assertThat(comments(n).get(1).message).doesNotContain("retained after crash");
  }

  @Test
  public void rejectedInsertionHasNoPhantomMessageAndRetryPublishesOnce() throws Exception {
    enable();
    var repo = cloneAs(user);
    commit(repo, "A", "full A after retry");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    commit(repo, "B", "full B after retry");
    var reject =
        new CommitValidationListener() {
          @Override
          public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent event)
              throws CommitValidationException {
            if (event.command.getRefName().startsWith("refs/changes/")) {
              throw new CommitValidationException("injected rejection");
            }
            return List.of();
          }
        };
    try (var registration = extensionRegistry.newRegistration().add(reject)) {
      var result = push(repo, FEATURE);
      assertOk(result, FEATURE);
      assertThat(joined(result)).contains("NOT synchronized");
      assertThat(comments(n)).hasSize(1);
      assertThat(publicationRefs(n)).hasSize(1);
      assertThat(change(n).revisions).hasSize(1);
    }
    syncViaRest(user, FEATURE, new PostSync.Input());
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertThat(comments(n)).hasSize(2);
    assertThat(comments(n).get(1).message).contains("full B after retry");
    assertThat(comments(n).get(1).message).doesNotContain("full A after retry");
  }

  @Test
  public void commentOnlyKeepsReviewStateAndEmitsANormalEvent() throws Exception {
    enable();
    var repo = cloneAs(user);
    var a = commit(repo, "A", "initial body");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    var api = gApi.changes().id(project.get(), n);
    api.setReadyForReview();
    api.addReviewer(admin.email());
    api.current().review(new ReviewInput().label("Code-Review", 2));
    api.topic("human topic");
    api.setHashtags(new HashtagsInput(Set.of("human")));
    var before =
        gApi.changes()
            .id(project.get(), n)
            .get(
                com.google.gerrit.extensions.client.ListChangesOption.LABELS,
                com.google.gerrit.extensions.client.ListChangesOption.DETAILED_LABELS);
    assertThat(
            before.labels.get("Code-Review").all.stream()
                .filter(v -> v._accountId.equals(admin.id().get()))
                .map(v -> v.value)
                .toList())
        .containsExactly(2);
    var events = new AtomicInteger();
    var listener =
        new CommentAddedListener() {
          @Override
          public void onCommentAdded(Event event) {
            if (event.getComment().contains("new comment-only body")) {
              events.incrementAndGet();
            }
          }
        };
    try (var registration = extensionRegistry.newRegistration().add(listener)) {
      repo.reset(a);
      var amended =
          repo.amend(a)
              .author(user.newIdent())
              .committer(user.newIdent())
              .message("Reworded\n\nnew comment-only body")
              .create();
      repo.reset(amended);
      assertOk(push(repo, "HEAD:" + FEATURE, true), FEATURE);
    }
    var after =
        gApi.changes()
            .id(project.get(), n)
            .get(
                com.google.gerrit.extensions.client.ListChangesOption.LABELS,
                com.google.gerrit.extensions.client.ListChangesOption.DETAILED_LABELS);
    assertThat(after.workInProgress).isEqualTo(before.workInProgress);
    assertThat(after.topic).isEqualTo(before.topic);
    assertThat(after.hashtags).isEqualTo(before.hashtags);
    assertThat(after.reviewers).isEqualTo(before.reviewers);
    assertThat(
            after.labels.get("Code-Review").all.stream()
                .map(v -> v._accountId + ":" + v.value)
                .toList())
        .containsExactlyElementsIn(
            before.labels.get("Code-Review").all.stream()
                .map(v -> v._accountId + ":" + v.value)
                .toList());
    assertThat(change(n).revisions).hasSize(1);
    assertThat(comments(n)).hasSize(2);
    assertThat(comments(n).get(1).author._accountId).isEqualTo(user.id().get());
    assertThat(events.get()).isEqualTo(1);
  }

  @Test
  public void initialMergePublishesParentMessagesButNotTheMergeMessage() throws Exception {
    enable();
    var repo = cloneAs(user);
    var a = commit(repo, "A", "parent A body");
    var b = commit(repo, "B", "parent B body");
    repo.reset(a);
    var side = commit(repo, "Side", "side body");
    var merge =
        repo.commit()
            .parent(b)
            .parent(side)
            .add("Side.txt", "Side")
            .message("merge body is excluded")
            .author(user.newIdent())
            .committer(user.newIdent())
            .create();
    repo.reset(merge);
    assertOk(push(repo, FEATURE), FEATURE);
    String message = comments(onlyChange()).get(0).message;
    assertThat(message).contains("parent A body");
    assertThat(message).contains("parent B body");
    assertThat(message).contains("side body");
    assertThat(message).doesNotContain("merge body is excluded");
  }

  private RevCommit commit(TestRepository<InMemoryRepository> repo, String subject, String body)
      throws Exception {
    return repo.branch("HEAD")
        .commit()
        .author(user.newIdent())
        .committer(user.newIdent())
        .message(subject + "\n\n" + body)
        .add(subject + ".txt", subject)
        .create();
  }

  private List<ChangeMessageInfo> comments(int number) throws Exception {
    return gApi.changes().id(project.get(), number).messages().stream()
        .filter(m -> m.message.contains("Source commit messages (branch-review)"))
        .toList();
  }

  @Test
  public void emptyNetContributionKeepsTheNewMessageWithoutAFakePatchSet() throws Exception {
    enable();
    var repo = cloneAs(user);
    commit(repo, "A", "initial body");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    repo.branch("HEAD")
        .commit()
        .author(user.newIdent())
        .committer(user.newIdent())
        .message("Revert A\n\nWhy the work was reverted\n")
        .rm("A.txt")
        .create();
    assertOk(push(repo, FEATURE), FEATURE);
    assertThat(change(n).revisions).hasSize(1);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.EMPTY);
    assertThat(comments(n)).hasSize(2);
    assertThat(comments(n).get(1).message).contains("Why the work was reverted");
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertThat(comments(n)).hasSize(2);
  }

  @Test
  public void sameCodeAmendPublishesOnlyANewDiscussionMessage() throws Exception {
    enable();
    var repo = cloneAs(user);
    var tip = commit(repo, "A", "original body");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    var before = change(n);
    repo.reset(tip);
    var amended =
        repo.amend(tip)
            .author(user.newIdent())
            .committer(user.newIdent())
            .message("A reworded\n\nnew full body\n\nBug: 123\n")
            .create();
    repo.reset(amended);
    assertOk(push(repo, "HEAD:" + FEATURE, true), FEATURE);
    assertThat(change(n).revisions).hasSize(1);
    assertThat(change(n).currentRevision).isEqualTo(before.currentRevision);
    assertThat(comments(n)).hasSize(2);
    assertThat(comments(n).get(1).message).contains("new full body\n\nBug: 123");
    // A failure between the comment transaction and lifecycle completion must not repost.
    try (var r = repoManager.openRepository(project)) {
      var loaded = stateStore.loadLifecycle(r, FEATURE);
      loaded.value().lastSourceId = tip.name();
      loaded.value().syncState = Lifecycle.SyncState.FAILED;
      stateStore.saveLifecycle(
          r, project, loaded.value(), loaded.refId(), "simulate comment completion failure");
    }
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertThat(comments(n)).hasSize(2);
    // Returning to a formerly published hash is not a new source commit.
    repo.reset(tip);
    assertOk(push(repo, "HEAD:" + FEATURE, true), FEATURE);
    assertThat(comments(n)).hasSize(2);
  }

  @Test
  public void groupsInitialAndFreshMessagesIndependentlyOfDescription() throws Exception {
    enable();
    var repo = cloneAs(user);
    var a = commit(repo, "A", "Full A body\n\nSigned-off-by: A <a@example.org>\n");
    var b = commit(repo, "B", "Full B body\nsecond line\n");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    assertThat(comments(n)).hasSize(1);
    String first = comments(n).get(0).message;
    assertThat(first).contains(a.abbreviate(12).name());
    assertThat(first).contains("Full A body\n\nSigned-off-by: A <a@example.org>");
    assertThat(first).contains("Full B body\nsecond line");
    assertThat(first.indexOf("Full A body")).isLessThan(first.indexOf("Full B body"));
    assertThat(comments(n).get(0).author._accountId).isEqualTo(user.id().get());

    var api = gApi.changes().id(project.get(), n);
    api.setMessage("Human subject\n\nHuman description\n\nChange-Id: " + change(n).changeId + "\n");
    var c = commit(repo, "C", "Full C body\n\nBug: original trailer\n");
    var d = commit(repo, "D", "Full D body\nlast line\n");
    assertOk(push(repo, FEATURE), FEATURE);
    assertThat(comments(n)).hasSize(2);
    String second = comments(n).get(1).message;
    assertThat(second).contains(c.abbreviate(12).name());
    assertThat(second).contains(d.abbreviate(12).name());
    assertThat(second).contains("Full C body\n\nBug: original trailer");
    assertThat(second).contains("Full D body\nlast line");
    assertThat(second).doesNotContain("Full A body");
    assertThat(second).doesNotContain("Full B body");
    assertThat(change(n).subject).isEqualTo("Human subject");
    assertThat(change(n).revisions.get(change(n).currentRevision).commit.message)
        .contains("Human description");
    syncViaRest(user, FEATURE, new PostSync.Input());
    serverCommit("refs/heads/master", "target.txt", "target only");
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertThat(comments(n)).hasSize(2);
    assertThat(b).isNotEqualTo(a);
  }
}
