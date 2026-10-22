// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.text;

/** Removes control characters while keeping newlines, tabs and printable Unicode. */
public final class Sanitizer {
  private Sanitizer() {}

  public static String normalizeNewlines(String s) {
    return s.replace("\r\n", "\n").replace('\r', '\n');
  }

  public static String clean(String s) {
    if (s == null) {
      return "";
    }
    String in = normalizeNewlines(s);
    StringBuilder out = new StringBuilder(in.length());
    in.codePoints()
        .forEach(
            cp -> {
              if (cp == '\n' || cp == '\t') {
                out.appendCodePoint(cp);
              } else if (Character.isISOControl(cp)
                  || Character.getType(cp) == Character.FORMAT && cp != 0x200D
                  || cp == 0x2028
                  || cp == 0x2029) {
                // Drop C0/C1 controls and bidi/format overrides; keep ZWJ for emoji sequences.
              } else {
                out.appendCodePoint(cp);
              }
            });
    return out.toString();
  }

  /** A single line: newlines and tabs collapsed to spaces, whitespace runs collapsed. */
  public static String oneLine(String s) {
    return clean(s).replaceAll("[\\n\\t]+", " ").replaceAll(" {2,}", " ").strip();
  }

  /** Truncates to at most {@code max} code points, appending an ellipsis when shortened. */
  public static String truncate(String s, int max) {
    if (s.codePointCount(0, s.length()) <= max) {
      return s;
    }
    int end = s.offsetByCodePoints(0, max - 1);
    return s.substring(0, end).stripTrailing() + "…";
  }
}
