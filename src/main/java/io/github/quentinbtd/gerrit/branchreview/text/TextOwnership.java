// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.text;

import com.google.common.collect.ImmutableList;

/**
 * Decides which parts of the commit message are still owned by the generator.
 *
 * <p>Rule: a field (subject or body) is generator-owned until the published text differs from the
 * last text the generator published for that field. From then on it is user-owned and stays so,
 * even if the user later types the generated text again; only an explicit, permission-checked reset
 * returns ownership to the generator. The subject and body are tracked independently.
 *
 * <p>The trailer block of the published message is preserved verbatim except that {@code Change-Id}
 * is forced to the lifecycle's synthetic key and placed last.
 */
public final class TextOwnership {
  private TextOwnership() {}

  public enum Owner {
    GENERATOR,
    USER
  }

  /** Previously recorded ownership state. */
  public record State(
      String lastGeneratedSubject, String lastGeneratedBody, Owner subject, Owner body) {
    public static State initial() {
      return new State(null, null, Owner.GENERATOR, Owner.GENERATOR);
    }
  }

  public record Result(String message, State state) {}

  /**
   * Computes the next message.
   *
   * @param published message of the current patch set, or {@code null} for a new change
   * @param prior recorded state
   * @param generated freshly generated text
   * @param changeKey the lifecycle's synthetic Change-Id
   */
  public static Result next(
      String published, State prior, MessageGenerator.Generated generated, String changeKey) {
    Owner subjectOwner = prior.subject();
    Owner bodyOwner = prior.body();
    String subject = generated.subject();
    String body = generated.body();
    ImmutableList.Builder<CommitMessage.Footer> footers = ImmutableList.builder();

    if (published != null) {
      CommitMessage current = CommitMessage.parse(published);
      if (subjectOwner == Owner.GENERATOR
          && !current
              .subject()
              .equals(Sanitizer.oneLine(nullToEmpty(prior.lastGeneratedSubject())))) {
        subjectOwner = Owner.USER;
      }
      if (bodyOwner == Owner.GENERATOR
          && !current.body().equals(CommitMessage.normalizeBody(prior.lastGeneratedBody()))) {
        bodyOwner = Owner.USER;
      }
      if (subjectOwner == Owner.USER) {
        subject = current.subject();
      }
      if (bodyOwner == Owner.USER) {
        body = current.body();
      }
      for (CommitMessage.Footer f : current.footers()) {
        if (!f.key().equalsIgnoreCase(CommitMessage.CHANGE_ID)) {
          footers.add(f);
        }
      }
    }
    footers.add(new CommitMessage.Footer(CommitMessage.CHANGE_ID, changeKey));
    String message = new CommitMessage(subject, body, footers.build()).render();
    return new Result(
        message, new State(generated.subject(), generated.body(), subjectOwner, bodyOwner));
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
