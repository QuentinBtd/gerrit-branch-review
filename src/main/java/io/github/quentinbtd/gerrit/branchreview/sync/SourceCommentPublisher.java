// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.sync;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.change.EmailReviewComments;
import com.google.gerrit.server.extensions.events.CommentAdded;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.ChangeContext;
import com.google.gerrit.server.update.PostUpdateContext;
import com.google.gerrit.server.update.RepoContext;
import com.google.gerrit.server.update.RepoOnlyOp;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/** A normal NoteDb discussion update, without a fake patch set or review/vote mutations. */
@Singleton
class SourceCommentPublisher {
  private final ChangeMessagesUtil messages;
  private final EmailReviewComments.Factory email;
  private final CommentAdded commentAdded;

  @Inject
  SourceCommentPublisher(
      ChangeMessagesUtil messages, EmailReviewComments.Factory email, CommentAdded commentAdded) {
    this.messages = messages;
    this.email = email;
    this.commentAdded = commentAdded;
  }

  /**
   * Publication evidence lives in the protected state namespace, never in caller-controlled tags.
   * Repo updates and NoteDb updates share Gerrit's atomic project BatchRefUpdate.
   */
  static void recordPublication(
      BatchUpdate bu, Change.Id change, String lifecycle, ObjectId source) {
    String ref = publicationPrefix(change, lifecycle) + source.name();
    bu.addRepoOnlyOp(
        new RepoOnlyOp() {
          @Override
          public void updateRepo(RepoContext ctx) throws IOException {
            var existing = ctx.getRepoView().getRef(ref);
            if (existing.isPresent()) {
              if (!existing.get().equals(source)) {
                throw new IOException("invalid source publication ref: " + ref);
              }
              return;
            }
            ctx.addRefUpdate(ObjectId.zeroId(), source, ref);
          }
        });
  }

  static List<ObjectId> publishedHeads(Repository repo, Change.Id change, String lifecycle)
      throws IOException {
    String prefix = publicationPrefix(change, lifecycle);
    var heads = new ArrayList<ObjectId>();
    for (var ref : repo.getRefDatabase().getRefsByPrefix(prefix)) {
      String head = ref.getName().substring(prefix.length());
      if (ref.isSymbolic() || !ObjectId.isId(head) || !head.equals(ref.getObjectId().name())) {
        throw new IOException("invalid source publication ref: " + ref.getName());
      }
      heads.add(ref.getObjectId());
    }
    return List.copyOf(heads);
  }

  private static String publicationPrefix(Change.Id change, String lifecycle) {
    return BranchReviewConfig.STATE_NAMESPACE
        + "publications/"
        + change.get()
        + "/"
        + lifecycle
        + "/";
  }

  void add(BatchUpdate bu, ChangeNotes notes, String message, String tag) {
    PatchSet ps = notes.getCurrentPatchSet();
    bu.addOp(
        notes.getChangeId(),
        new BatchUpdateOp() {
          private String mailMessage;

          @Override
          public boolean updateChange(ChangeContext ctx) {
            mailMessage = messages.setChangeMessage(ctx.getUpdate(ps.id()), message, tag);
            return true;
          }

          @Override
          public void postUpdate(PostUpdateContext ctx) {
            email
                .create(ctx, ps, notes.getMetaId(), mailMessage, List.of(), message, List.of())
                .sendAsync();
            commentAdded.fire(
                ctx.getChangeData(notes),
                ps,
                ctx.getUser().asIdentifiedUser().state(),
                mailMessage,
                Map.of(),
                Map.of(),
                ctx.getWhen());
          }
        });
  }
}
