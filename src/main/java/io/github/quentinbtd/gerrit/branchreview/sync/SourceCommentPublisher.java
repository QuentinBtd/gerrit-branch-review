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
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

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
              validatePublication(ctx.getRevWalk(), existing.get(), ref, change, lifecycle, source);
              return;
            }
            // Textual source OIDs are not Git links. Never attach a source tree or parent:
            // namespace READ must not become authorization to fetch raw source history.
            var commit = new CommitBuilder();
            commit.setTreeId(ctx.getInserter().insert(new TreeFormatter()));
            var ident = ctx.newCommitterIdent();
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(publicationMessage(change, lifecycle, source));
            var evidence = ctx.getInserter().insert(commit);
            ctx.addRefUpdate(ObjectId.zeroId(), evidence, ref);
          }
        });
  }

  static List<ObjectId> publishedHeads(Repository repo, Change.Id change, String lifecycle)
      throws IOException {
    String prefix = publicationPrefix(change, lifecycle);
    var heads = new ArrayList<ObjectId>();
    try (var rw = new RevWalk(repo)) {
      for (var ref : repo.getRefDatabase().getRefsByPrefix(prefix)) {
        String head = ref.getName().substring(prefix.length());
        if (ref.isSymbolic() || !ObjectId.isId(head)) {
          throw new IOException("invalid source publication ref: " + ref.getName());
        }
        ObjectId source = ObjectId.fromString(head);
        if (!head.equals(source.name())) {
          throw new IOException("invalid source publication ref: " + ref.getName());
        }
        validatePublication(rw, ref.getObjectId(), ref.getName(), change, lifecycle, source);
        heads.add(source);
      }
    }
    return List.copyOf(heads);
  }

  private static String publicationMessage(Change.Id change, String lifecycle, ObjectId source) {
    return "branch-review publication v1\n"
        + "change "
        + change.get()
        + "\n"
        + "lifecycle "
        + lifecycle
        + "\n"
        + "source "
        + source.name()
        + "\n";
  }

  private static void validatePublication(
      RevWalk rw,
      ObjectId evidence,
      String ref,
      Change.Id change,
      String lifecycle,
      ObjectId source)
      throws IOException {
    // parseAny deliberately does not peel tags: only the metadata commit itself is evidence.
    var object = rw.parseAny(evidence);
    if (!(object instanceof RevCommit commit)
        || commit.getParentCount() != 0
        || !commit.getTree().equals(Constants.EMPTY_TREE_ID)
        || !commit.getFullMessage().equals(publicationMessage(change, lifecycle, source))) {
      throw new IOException("invalid source publication ref: " + ref);
    }
    rw.parseTree(commit.getTree());
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
