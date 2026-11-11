// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;

import io.github.quentinbtd.gerrit.branchreview.api.PostSync;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import java.util.List;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

public class PublishCommitMessagesIT extends AbstractBranchReviewIT {
  private static final String FEATURE = "refs/heads/feature/messages-option";

  @Test
  public void skippedHistoryStaysExcludedAfterEnabledRewrite() throws Exception {
    rewriteDoesNotBackfill(false, false);
  }

  @Test
  public void recoveredDisabledInsertionKeepsItsPublicationPolicy() throws Exception {
    rewriteDoesNotBackfill(true, false);
  }

  @Test
  public void upgradeBaselineSurvivesAnEnabledRewrite() throws Exception {
    rewriteDoesNotBackfill(false, true);
  }

  private void rewriteDoesNotBackfill(boolean recover, boolean legacy) throws Exception {
    setConfig(DEFAULT_CONFIG + " publishCommitMessages = false\n");
    var repo = cloneAs(user);
    var skipped = commit(repo, "A", "skipped source discussion body");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    assertState(n, skipped, 1, 0, 0);
    if (recover || legacy) {
      try (var r = repoManager.openRepository(project)) {
        var loaded = stateStore.loadLifecycle(r, FEATURE);
        var lc = loaded.value();
        lc.sourceMessageBaselines = null;
        if (recover) {
          lc.pending = new Lifecycle.Pending();
          lc.pending.op = "create";
          lc.pending.changeNumber = n;
          lc.pending.patchSet = 1;
          lc.pending.commit = lc.lastSyntheticCommit;
          lc.pending.sourceId = lc.lastSourceId;
          lc.pending.targetId = lc.lastTargetId;
          lc.pending.publishCommitMessages = false;
          lc.changeNumber = 0;
          lc.lastSyntheticPatchSet = 0;
          lc.lastSourceId = null;
          lc.syncState = Lifecycle.SyncState.PENDING;
        }
        stateStore.saveLifecycle(
            r, project, lc, loaded.refId(), "simulate upgrade or pre-completion crash");
      }
    }
    setConfig(DEFAULT_CONFIG + " publishCommitMessages = true\n");
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertState(n, skipped, 1, 0, 0);
    var rewritten =
        repo.amend(skipped)
            .author(user.newIdent())
            .committer(user.newIdent())
            .message("Rewritten\n\nfresh enabled discussion body")
            .create();
    repo.reset(rewritten);
    assertOk(push(repo, "HEAD:" + FEATURE, true), FEATURE);
    assertState(n, rewritten, 1, 1, 1);
    assertThat(comments(n).get(0).message).contains("fresh enabled discussion body");
    assertThat(comments(n).get(0).message).doesNotContain("skipped source discussion body");
    repo.reset(skipped);
    assertOk(push(repo, "HEAD:" + FEATURE, true), FEATURE);
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertState(n, skipped, 1, 1, 1);
    var fresh = commit(repo, "Fresh", "fresh descendant of skipped history");
    assertOk(push(repo, FEATURE), FEATURE);
    assertState(n, fresh, 2, 2, 2);
    assertThat(comments(n).get(1).message).contains("fresh descendant of skipped history");
    assertThat(comments(n).get(1).message).doesNotContain("skipped source discussion body");
  }

  @Test
  public void inheritedFalseKeepsSyncThroughCreateUpdateAmendEmptyAndReenable() throws Exception {
    var parent = projectOperations.newProject().createEmptyCommit(false).create();
    gApi.projects().name(project.get()).parent(parent.get());
    projectCache.evictAndReindex(project);
    setConfig(parent, DEFAULT_CONFIG + " publishCommitMessages = false\n");
    var repo = cloneAs(user);
    var a = commit(repo, "A", "disabled initial body");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    assertState(n, a, 1, 0, 0);
    assertThat(fileAt(ObjectId.fromString(change(n).currentRevision), "A.txt")).isEqualTo("A");
    assertThat(
            gApi.changes().id(project.get(), n).messages().stream()
                .anyMatch(m -> m.message.contains("Uploaded patch set 1")))
        .isTrue();
    var b = commit(repo, "B", "disabled update body");
    assertOk(push(repo, FEATURE), FEATURE);
    assertState(n, b, 2, 0, 0);
    assertThat(fileAt(ObjectId.fromString(change(n).currentRevision), "B.txt")).isEqualTo("B");
    var amend =
        repo.amend(b)
            .author(user.newIdent())
            .committer(user.newIdent())
            .message("B reworded\n\ndisabled equivalent body")
            .create();
    repo.reset(amend);
    assertOk(push(repo, "HEAD:" + FEATURE, true), FEATURE);
    assertState(n, amend, 2, 0, 0);
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertState(n, amend, 2, 0, 0);
    var empty =
        repo.branch("HEAD")
            .commit()
            .author(user.newIdent())
            .committer(user.newIdent())
            .message("Undo source\n\ndisabled empty body")
            .rm("A.txt")
            .rm("B.txt")
            .create();
    assertOk(push(repo, FEATURE), FEATURE);
    assertThat(lifecycle(FEATURE).syncState).isEqualTo(Lifecycle.SyncState.EMPTY);
    assertState(n, empty, 2, 0, 0);
    // Child overrides the inherited switch; no catch-up even on an EMPTY resync.
    setConfig("[branchReview]\n publishCommitMessages = true\n");
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertState(n, empty, 2, 0, 0);
    var c = commit(repo, "C", "enabled fresh body");
    assertOk(push(repo, FEATURE), FEATURE);
    assertState(n, c, 3, 1, 1);
    assertThat(comments(n).get(0).message).contains("enabled fresh body");
    assertThat(comments(n).get(0).message).doesNotContain("disabled");
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertState(n, c, 3, 1, 1);
    // B was rewritten away while disabled, so the enabled C marker does not cover it.
    repo.reset(b);
    assertOk(push(repo, "HEAD:" + FEATURE, true), FEATURE);
    assertState(n, b, 4, 1, 1);
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertState(n, b, 4, 1, 1);
  }

  @Test
  public void falsePreservesExistingDiscussionAndIgnoresCorruptEvidence() throws Exception {
    enable();
    var repo = cloneAs(user);
    var a = commit(repo, "A", "retained enabled body");
    assertOk(push(repo, FEATURE), FEATURE);
    int n = onlyChange();
    assertState(n, a, 1, 1, 1);
    var originalMessage = comments(n).get(0).message;
    var originalRefs = publicationRefs();
    var marker = originalRefs.get(0);
    setConfig(DEFAULT_CONFIG + " publishCommitMessages = false\n");
    try (var r = repoManager.openRepository(project);
        var inserter = r.newObjectInserter();
        var ctx =
            com.google.gerrit.server.update.context.RefUpdateContext.open(
                com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType.PLUGIN)) {
      var corrupt = inserter.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, new byte[0]);
      inserter.flush();
      var update = r.updateRef(marker.getName());
      update.setForceUpdate(true);
      update.setNewObjectId(corrupt);
      update.update();
    }
    var b = commit(repo, "B", "disabled middle body");
    var result = push(repo, FEATURE);
    assertOk(result, FEATURE);
    assertThat(joined(result)).doesNotContain("NOT synchronized");
    assertState(n, b, 2, 1, 1);
    assertThat(refValue(marker.getName())).isEqualTo(org.eclipse.jgit.lib.Constants.EMPTY_BLOB_ID);
    assertThat(comments(n).get(0).message).isEqualTo(originalMessage);
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertState(n, b, 2, 1, 1);
    // Restore only the deliberate test corruption, never erase publication evidence.
    try (var r = repoManager.openRepository(project);
        var ctx =
            com.google.gerrit.server.update.context.RefUpdateContext.open(
                com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType.PLUGIN)) {
      var update = r.updateRef(marker.getName());
      update.setForceUpdate(true);
      update.setNewObjectId(marker.getObjectId());
      update.update();
    }
    var restored = publicationRefs();
    assertThat(restored).hasSize(1);
    assertThat(restored.get(0).getName()).isEqualTo(marker.getName());
    assertThat(restored.get(0).getObjectId()).isEqualTo(marker.getObjectId());
    setConfig(DEFAULT_CONFIG + " publishCommitMessages = true\n");
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertState(n, b, 2, 1, 1);
    var c = commit(repo, "C", "enabled later body");
    assertOk(push(repo, FEATURE), FEATURE);
    assertState(n, c, 3, 2, 2);
    assertThat(comments(n).get(0).message).isEqualTo(originalMessage);
    assertThat(comments(n).get(1).message).contains("enabled later body");
    assertThat(comments(n).get(1).message).doesNotContain("disabled middle body");
    syncViaRest(user, FEATURE, new PostSync.Input());
    assertState(n, c, 3, 2, 2);
  }

  private void assertState(int n, RevCommit source, int patchSets, int messages, int markers)
      throws Exception {
    assertThat(lifecycle(FEATURE).lastSourceId).isEqualTo(source.name());
    assertThat(change(n).revisions).hasSize(patchSets);
    assertThat(comments(n)).hasSize(messages);
    assertThat(publicationRefs()).hasSize(markers);
  }

  private List<org.eclipse.jgit.lib.Ref> publicationRefs() throws Exception {
    try (var r = repoManager.openRepository(project)) {
      return r.getRefDatabase().getRefsByPrefix("refs/meta/branch-review/publications/");
    }
  }

  private List<com.google.gerrit.extensions.common.ChangeMessageInfo> comments(int n)
      throws Exception {
    return gApi.changes().id(project.get(), n).messages().stream()
        .filter(m -> m.message.contains("Source commit messages (branch-review)"))
        .toList();
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
}
