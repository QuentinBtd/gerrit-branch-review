// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.config;

import org.eclipse.jgit.lib.Repository;

/**
 * A source/exclusion branch pattern over full ref names.
 *
 * <p>Grammar: either an exact ref name such as {@code refs/heads/release-notes}, or a prefix
 * followed by a single trailing {@code *} such as {@code refs/heads/feature/*}. The {@code *}
 * matches any (possibly empty) remainder, including further {@code /} separators. No other
 * wildcard, character class or regular expression syntax is accepted; Gerrit ACL regular
 * expressions ({@code ^...}) are rejected rather than reinterpreted.
 */
public final class RefPattern {
  private final String pattern;
  private final String prefix;
  private final boolean wildcard;

  private RefPattern(String pattern) {
    this.pattern = pattern;
    this.wildcard = pattern.endsWith("*");
    this.prefix = wildcard ? pattern.substring(0, pattern.length() - 1) : pattern;
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
          "regular expressions are not supported; use an exact ref or a trailing '*'");
    }
    if (!raw.startsWith("refs/heads/")) {
      throw new IllegalArgumentException("pattern must start with refs/heads/");
    }
    int star = raw.indexOf('*');
    if (star >= 0 && star != raw.length() - 1) {
      throw new IllegalArgumentException("'*' is only supported as the last character");
    }
    for (char c : new char[] {'?', '[', ']', '\\', '{', '}', '$', '+', '|', '(', ')'}) {
      if (raw.indexOf(c) >= 0) {
        throw new IllegalArgumentException("unsupported character '" + c + "'");
      }
    }
    String probe = star >= 0 ? raw.substring(0, star) + "x" : raw;
    if (!Repository.isValidRefName(probe)) {
      throw new IllegalArgumentException("not a valid ref name pattern");
    }
    if (star < 0 && raw.equals("refs/heads/")) {
      throw new IllegalArgumentException("pattern must name a branch");
    }
    return new RefPattern(raw);
  }

  public boolean matches(String refName) {
    return wildcard
        ? refName.startsWith(prefix) && refName.length() > prefix.length()
        : refName.equals(pattern);
  }

  /** The literal part before the wildcard, or the whole ref for exact patterns. */
  public String prefix() {
    return prefix;
  }

  public boolean isWildcard() {
    return wildcard;
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
