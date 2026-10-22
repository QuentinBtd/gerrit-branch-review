// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.text;

import com.google.common.collect.ImmutableList;
import io.github.quentinbtd.gerrit.branchreview.config.RefPattern;
import java.util.List;
import java.util.Locale;

/**
 * Generates the automatic subject and body of a synthetic squash commit.
 *
 * <p>Algorithm (deterministic for a given source state):
 *
 * <ul>
 *   <li>If the contribution has exactly one non-merge commit, its subject and body (without its
 *       trailer block) are used.
 *   <li>Otherwise the subject is derived from the branch name: {@code refs/heads/} and the literal
 *       prefix of the matching wildcard pattern (e.g. {@code feature/}) are removed, runs of {@code
 *       -} and {@code _} become spaces and the first letter is capitalized. The body lists the
 *       non-merge contribution commits oldest first, one line each, bounded to {@link #MAX_LISTED}
 *       entries.
 * </ul>
 *
 * Control characters are removed; other Unicode is preserved.
 */
public final class MessageGenerator {
  public static final int MAX_SUBJECT = 72;
  public static final int MAX_LISTED = 50;
  public static final int MAX_BODY_CHARS = 8000;

  /** A commit that belongs to the contribution, as seen by the generator. */
  public record SourceCommit(String abbreviatedId, String fullMessage, boolean merge) {}

  public record Generated(String subject, String body) {}

  private MessageGenerator() {}

  public static Generated generate(
      String sourceRef, RefPattern matchedPattern, List<SourceCommit> oldestFirst) {
    List<SourceCommit> nonMerge = oldestFirst.stream().filter(c -> !c.merge()).toList();
    if (nonMerge.size() == 1) {
      CommitMessage m = CommitMessage.parse(Sanitizer.clean(nonMerge.get(0).fullMessage()));
      String subject = Sanitizer.oneLine(m.subject());
      if (!subject.isEmpty()) {
        return new Generated(
            Sanitizer.truncate(subject, MAX_SUBJECT * 2),
            boundBody(CommitMessage.normalizeBody(m.body())));
      }
    }
    String subject = subjectFromBranch(sourceRef, matchedPattern);
    StringBuilder body = new StringBuilder();
    int merges = oldestFirst.size() - nonMerge.size();
    body.append("Squash of ")
        .append(nonMerge.size())
        .append(nonMerge.size() == 1 ? " commit" : " commits")
        .append(" from ")
        .append(sourceRef);
    if (merges > 0) {
      body.append(" (")
          .append(merges)
          .append(merges == 1 ? " merge" : " merges")
          .append(" omitted)");
    }
    body.append(":\n");
    int listed = 0;
    for (SourceCommit c : nonMerge) {
      if (listed == MAX_LISTED) {
        body.append("\n* ... and ").append(nonMerge.size() - listed).append(" more");
        break;
      }
      String s = Sanitizer.oneLine(CommitMessage.parse(c.fullMessage()).subject());
      body.append("\n* ").append(c.abbreviatedId()).append(' ').append(Sanitizer.truncate(s, 100));
      listed++;
    }
    return new Generated(subject, boundBody(body.toString()));
  }

  static String subjectFromBranch(String sourceRef, RefPattern pattern) {
    String name = sourceRef.substring("refs/heads/".length());
    if (pattern != null && pattern.isWildcard() && sourceRef.startsWith(pattern.prefix())) {
      String rest = sourceRef.substring(pattern.prefix().length());
      if (!rest.isEmpty()) {
        name = rest;
      }
    }
    String words = Sanitizer.oneLine(name.replaceAll("[-_]+", " ")).replaceAll(" {2,}", " ");
    if (words.isEmpty()) {
      words = name;
    }
    String subject =
        words.substring(0, words.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT)
            + words.substring(words.offsetByCodePoints(0, 1));
    return Sanitizer.truncate(subject, MAX_SUBJECT);
  }

  private static String boundBody(String body) {
    String b = Sanitizer.clean(body);
    if (b.length() > MAX_BODY_CHARS) {
      b = b.substring(0, MAX_BODY_CHARS) + "\n[truncated]";
    }
    // A trailing paragraph that looks like trailers would be re-parsed as footers and break the
    // ownership comparison, so such paragraphs (e.g. Signed-off-by of a source commit) are dropped.
    while (true) {
      CommitMessage probe = CommitMessage.parse("x\n\n" + b);
      if (probe.footers().isEmpty()) {
        return probe.body();
      }
      b = probe.body();
    }
  }

  /** Convenience for tests. */
  public static ImmutableList<SourceCommit> commits(SourceCommit... c) {
    return ImmutableList.copyOf(c);
  }
}
