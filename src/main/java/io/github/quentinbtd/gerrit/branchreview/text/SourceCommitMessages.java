// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;

/** Source messages are discussion text, not inputs to squash text ownership. */
public final class SourceCommitMessages {
  public static final int MAX_MESSAGE_CHARS = 32000;
  public static final int MAX_COMMIT_CHARS = 8000;
  public static final int MAX_LISTED = 50;
  private static final String PREFIX = "branch-review:source-commits:v1:";

  public record Commit(String id, String message) {}

  private SourceCommitMessages() {}

  public static String tag(String lifecycle, ObjectId source) {
    return PREFIX + lifecycle + ":" + source.name();
  }

  /** Full object identities, never displayed abbreviations, are the reachability watermarks. */
  public static List<ObjectId> publishedHeads(String lifecycle, Collection<String> tags) {
    String prefix = PREFIX + lifecycle + ":";
    return tags.stream()
        .filter(t -> t != null && t.startsWith(prefix))
        .map(t -> t.substring(prefix.length()))
        .filter(ObjectId::isId)
        .map(ObjectId::fromString)
        .distinct()
        .toList();
  }

  /**
   * Oldest-first contribution, excluding every already published source history and merges.
   * Published heads whose objects are gone (rewritten history pruned by GC) are ignored: commits
   * still reachable from {@code source} are then listed again, which beats blocking the sync.
   */
  public static List<Commit> select(
      Repository repo, ObjectId source, ObjectId target, Collection<ObjectId> published, int limit)
      throws IOException {
    List<ObjectId> present = new ArrayList<>();
    for (ObjectId head : published) {
      if (repo.getObjectDatabase().has(head)) {
        present.add(head);
      }
    }
    try (RevWalk rw = new RevWalk(repo)) {
      rw.markStart(rw.parseCommit(source));
      rw.markUninteresting(rw.parseCommit(target));
      for (ObjectId head : present) {
        rw.markUninteresting(rw.parseCommit(head));
      }
      int count = 0;
      while (rw.next() != null) {
        if (++count > limit) {
          throw new IOException("source comment contribution exceeds maxCommits");
        }
      }
      rw.reset();
      rw.sort(RevSort.TOPO);
      rw.sort(RevSort.REVERSE, true);
      rw.markStart(rw.parseCommit(source));
      rw.markUninteresting(rw.parseCommit(target));
      for (ObjectId head : present) {
        rw.markUninteresting(rw.parseCommit(head));
      }
      List<Commit> commits = new ArrayList<>();
      for (RevCommit commit : rw) {
        if (commit.getParentCount() <= 1) {
          commits.add(new Commit(commit.name(), commit.getFullMessage()));
        }
      }
      return List.copyOf(commits);
    }
  }

  /**
   * Commits reachable from {@code source} but not from {@code target}, or an empty set when there
   * are more than {@code limit} (callers then skip an optional compaction). A watermark in this set
   * is redundant once {@code source} itself is a watermark.
   */
  public static Set<ObjectId> contribution(
      ObjectReader reader, ObjectId source, ObjectId target, int limit) throws IOException {
    Set<ObjectId> out = new HashSet<>();
    try (RevWalk rw = new RevWalk(reader)) {
      rw.markStart(rw.parseCommit(source));
      rw.markUninteresting(rw.parseCommit(target));
      for (RevCommit c : rw) {
        if (out.size() == limit) {
          return Set.of();
        }
        out.add(c.copy());
      }
    }
    return out;
  }

  /** Keeps trailers and all paragraphs, subject only to explicitly reported output limits. */
  public static String render(List<Commit> commits) {
    if (commits.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder("Source commit messages (branch-review)\n");
    int listed = 0;
    for (Commit commit : commits) {
      // Gerrit interprets account templates even in plain discussion messages. Keep source
      // text literal instead of impersonating an account reference in the UI or notifications.
      String message =
          Sanitizer.clean(commit.message()).replace("<GERRIT_ACCOUNT_", "< GERRIT_ACCOUNT_");
      if (message.length() > MAX_COMMIT_CHARS) {
        int end = MAX_COMMIT_CHARS;
        if (Character.isHighSurrogate(message.charAt(end - 1))) {
          end--;
        }
        message = message.substring(0, end) + "\n[commit message truncated]";
      }
      String entry = "\n" + commit.id().substring(0, 12) + "\n" + message.stripTrailing() + "\n";
      if (listed == MAX_LISTED || out.length() + entry.length() > MAX_MESSAGE_CHARS - 100) {
        break;
      }
      out.append(entry);
      listed++;
    }
    if (listed < commits.size()) {
      out.append("\n[truncated: ")
          .append(commits.size() - listed)
          .append(" additional commit messages omitted]\n");
    }
    return out.toString();
  }
}
