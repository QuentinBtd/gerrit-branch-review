// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.text;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A commit message split into subject, body and trailing footer block.
 *
 * <p>The footer block is the last paragraph when every line in it looks like a Git trailer ({@code
 * Key: value}). Everything between the subject and the footer block is the body.
 */
public record CommitMessage(String subject, String body, ImmutableList<Footer> footers) {
  private static final Pattern FOOTER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*: .*$");
  public static final String CHANGE_ID = "Change-Id";

  public record Footer(String key, String value) {
    @Override
    public String toString() {
      return key + ": " + value;
    }
  }

  public static CommitMessage parse(String raw) {
    String text = Sanitizer.normalizeNewlines(raw == null ? "" : raw);
    List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    trimTrailingBlank(lines);
    if (lines.isEmpty()) {
      return new CommitMessage("", "", ImmutableList.of());
    }
    // Subject: first paragraph joined (Git semantics), usually a single line.
    int i = 0;
    StringBuilder subject = new StringBuilder();
    while (i < lines.size() && !lines.get(i).isBlank()) {
      if (subject.length() > 0) {
        subject.append(' ');
      }
      subject.append(lines.get(i).strip());
      i++;
    }
    List<String> rest = new ArrayList<>(lines.subList(i, lines.size()));
    trimLeadingBlank(rest);

    ImmutableList<Footer> footers = ImmutableList.of();
    int lastBlank = -1;
    for (int j = rest.size() - 1; j >= 0; j--) {
      if (rest.get(j).isBlank()) {
        lastBlank = j;
        break;
      }
    }
    List<String> lastParagraph = rest.subList(lastBlank + 1, rest.size());
    if (!lastParagraph.isEmpty()
        && lastParagraph.stream().allMatch(l -> FOOTER.matcher(l).matches())) {
      ImmutableList.Builder<Footer> b = ImmutableList.builder();
      for (String l : lastParagraph) {
        int colon = l.indexOf(':');
        b.add(new Footer(l.substring(0, colon), l.substring(colon + 1).strip()));
      }
      footers = b.build();
      rest = new ArrayList<>(rest.subList(0, Math.max(lastBlank, 0)));
    }
    trimTrailingBlank(rest);
    return new CommitMessage(subject.toString(), normalizeBody(String.join("\n", rest)), footers);
  }

  /** Renders subject, body and footers into a Git commit message ending in a newline. */
  public String render() {
    StringBuilder sb = new StringBuilder(subject.strip());
    sb.append('\n');
    String b = normalizeBody(body);
    if (!b.isEmpty()) {
      sb.append('\n').append(b).append('\n');
    }
    if (!footers.isEmpty()) {
      sb.append('\n');
      for (Footer f : footers) {
        sb.append(f).append('\n');
      }
    }
    return sb.toString();
  }

  /** Canonical body form used for comparisons: no trailing whitespace, no edge blank lines. */
  public static String normalizeBody(String body) {
    if (body == null) {
      return "";
    }
    List<String> lines = new ArrayList<>();
    for (String l : Sanitizer.normalizeNewlines(body).split("\n", -1)) {
      lines.add(l.stripTrailing());
    }
    trimLeadingBlank(lines);
    trimTrailingBlank(lines);
    return String.join("\n", lines);
  }

  private static void trimTrailingBlank(List<String> lines) {
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
      lines.remove(lines.size() - 1);
    }
  }

  private static void trimLeadingBlank(List<String> lines) {
    while (!lines.isEmpty() && lines.get(0).isBlank()) {
      lines.remove(0);
    }
  }
}
