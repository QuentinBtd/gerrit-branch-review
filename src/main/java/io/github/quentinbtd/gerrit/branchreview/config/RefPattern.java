// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.config;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.jgit.lib.Repository;

/**
 * A source/exclusion branch pattern over full ref names.
 *
 * <p>Grammar, segment by segment ({@code /}-separated):
 *
 * <ul>
 *   <li>a literal segment matches itself;
 *   <li>a {@code *} segment that is not the last one matches exactly one non-empty segment;
 *   <li>the last segment may end with {@code *} ({@code feature*} or just {@code *}): it matches
 *       any non-empty remainder starting with the text before {@code *}, including further {@code
 *       /} separators.
 * </ul>
 *
 * So {@code refs/heads/feature/*} matches {@code refs/heads/feature/a/b}, and {@code
 * refs/heads/*}{@code /feat/*} matches {@code refs/heads/alice/feat/x} but not {@code
 * refs/heads/a/b/feat/x}. No other wildcard, character class or regular expression syntax is
 * accepted; Gerrit ACL regular expressions ({@code ^...}) are rejected rather than reinterpreted.
 */
public final class RefPattern {
  private final String pattern;

  /** Segments before the last one: literal text, or {@code "*"} for one arbitrary segment. */
  private final ImmutableList<String> head;

  /** The last segment without its trailing {@code *}, if any. */
  private final String last;

  /** Whether the last segment ends with {@code *} (open remainder). */
  private final boolean tail;

  private RefPattern(String pattern, List<String> head, String last, boolean tail) {
    this.pattern = pattern;
    this.head = ImmutableList.copyOf(head);
    this.last = last;
    this.tail = tail;
  }

  /** Parses a pattern, throwing {@link IllegalArgumentException} with a user-facing reason. */
  public static RefPattern parse(String raw) {
    if (raw == null || raw.isEmpty()) {
      throw new IllegalArgumentException("pattern must not be empty");
    }
    if (!raw.equals(raw.strip())) {
      throw new IllegalArgumentException("pattern must not have leading or trailing whitespace");
    }
    if (raw.startsWith("^")) {
      throw new IllegalArgumentException(
          "regular expressions are not supported; use '*' for a whole segment or a trailing '*'");
    }
    if (!raw.startsWith("refs/heads/")) {
      throw new IllegalArgumentException("pattern must start with refs/heads/");
    }
    for (char c : new char[] {'?', '[', ']', '\\', '{', '}', '$', '+', '|', '(', ')'}) {
      if (raw.indexOf(c) >= 0) {
        throw new IllegalArgumentException("unsupported character '" + c + "'");
      }
    }
    if (raw.contains("**")) {
      throw new IllegalArgumentException("'**' is not supported");
    }
    String[] segs = raw.split("/", -1);
    for (int i = 0; i < segs.length - 1; i++) {
      if (segs[i].contains("*") && !segs[i].equals("*")) {
        throw new IllegalArgumentException(
            "'*' must be a whole segment, or at the end of the last segment");
      }
    }
    String lastSeg = segs[segs.length - 1];
    boolean tail = lastSeg.endsWith("*");
    String last = tail ? lastSeg.substring(0, lastSeg.length() - 1) : lastSeg;
    if (last.contains("*")) {
      throw new IllegalArgumentException(
          "'*' must be a whole segment, or at the end of the last segment");
    }
    if (!tail && last.isEmpty()) {
      throw new IllegalArgumentException("pattern must name a branch");
    }
    // Validate as a ref name with every wildcard replaced by a plausible segment.
    StringBuilder probe = new StringBuilder();
    for (int i = 0; i < segs.length - 1; i++) {
      probe.append(segs[i].equals("*") ? "x" : segs[i]).append('/');
    }
    probe.append(last).append(tail ? "x" : "");
    if (!Repository.isValidRefName(probe.toString())) {
      throw new IllegalArgumentException("not a valid ref name pattern");
    }
    List<String> head = new ArrayList<>(Arrays.asList(segs).subList(0, segs.length - 1));
    if (head.size() < 2 || head.get(0).equals("*") || head.get(1).equals("*")) {
      throw new IllegalArgumentException("pattern must start with refs/heads/");
    }
    return new RefPattern(raw, head, last, tail);
  }

  public boolean matches(String refName) {
    return remainderOf(refName) != null;
  }

  /**
   * What the trailing {@code *} matched in {@code refName} (e.g. {@code x/y} for {@code
   * refs/heads/feature/*} and {@code refs/heads/feature/x/y}); empty for exact patterns; {@code
   * null} if the pattern does not match.
   */
  public String remainderOf(String refName) {
    String[] segs = refName.split("/", -1);
    if (segs.length < head.size() + 1) {
      return null;
    }
    for (int i = 0; i < head.size(); i++) {
      String p = head.get(i);
      if (p.equals("*") ? segs[i].isEmpty() : !p.equals(segs[i])) {
        return null;
      }
    }
    if (!tail) {
      return segs.length == head.size() + 1 && segs[head.size()].equals(last) ? "" : null;
    }
    String rest = String.join("/", Arrays.asList(segs).subList(head.size(), segs.length));
    if (!rest.startsWith(last) || rest.length() == last.length()) {
      return null;
    }
    return rest.substring(last.length());
  }

  public boolean isWildcard() {
    return tail || head.contains("*");
  }

  /**
   * Compares how specific two patterns are; positive if this one is more specific. Used to pick one
   * route when patterns of several routes match the same branch, like a routing table.
   *
   * <p>Segments are compared from left to right; the first difference decides: a literal segment
   * beats a trailing-{@code *} segment, which beats a mid-path {@code *}, which beats a bare
   * trailing {@code *}; between two trailing-{@code *} segments the longer literal prefix wins. Two
   * different patterns that match a common branch never compare equal.
   */
  public int compareSpecificity(RefPattern other) {
    int n = Math.max(head.size(), other.head.size()) + 1;
    for (int i = 0; i < n; i++) {
      int[] a = score(i);
      int[] b = other.score(i);
      int c = a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  /** {kind, literal length} of segment {@code i}; kinds: 3 literal, 2 prefix*, 1 *, 0 final *. */
  private int[] score(int i) {
    if (i < head.size()) {
      String s = head.get(i);
      return s.equals("*") ? new int[] {1, 0} : new int[] {3, s.length()};
    }
    if (i == head.size()) {
      if (!tail) {
        return new int[] {3, last.length()};
      }
      return last.isEmpty() ? new int[] {0, 0} : new int[] {2, last.length()};
    }
    return new int[] {-1, 0};
  }

  @Override
  public String toString() {
    return pattern;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof RefPattern other && other.pattern.equals(pattern);
  }

  @Override
  public int hashCode() {
    return pattern.hashCode();
  }
}
