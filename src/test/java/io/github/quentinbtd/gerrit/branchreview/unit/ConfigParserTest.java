// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.unit;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import io.github.quentinbtd.gerrit.branchreview.config.ConfigParser;
import io.github.quentinbtd.gerrit.branchreview.config.RefPattern;
import org.junit.Test;

public class ConfigParserTest {
  private static final String VALID =
      """
      [branchReview]
        enabled = true
        targetBranch = refs/heads/master
        sourceBranchPattern = refs/heads/feature/*
        sourceBranchPattern = refs/heads/bugfix/*
        excludeBranchPattern = refs/heads/feature/private/*
        deleteSourceBranchOnSubmit = false
      """;

  @Test
  public void missingOrEmptyFileIsDisabled() {
    assertThat(ConfigParser.parseText(null).status()).isEqualTo(BranchReviewConfig.Status.DISABLED);
    assertThat(ConfigParser.parseText("  \n").status())
        .isEqualTo(BranchReviewConfig.Status.DISABLED);
  }

  @Test
  public void enabledMustBeExplicit() {
    assertThat(
            ConfigParser.parseText(
                    "[branchReview]\n targetBranch = refs/heads/master\n"
                        + " sourceBranchPattern = refs/heads/f/*\n")
                .status())
        .isEqualTo(BranchReviewConfig.Status.DISABLED);
    assertThat(ConfigParser.parseText(VALID.replace("enabled = true", "enabled = false")).status())
        .isEqualTo(BranchReviewConfig.Status.DISABLED);
  }

  @Test
  public void validConfig() {
    BranchReviewConfig c = ConfigParser.parseText(VALID);
    assertThat(c.isEnabled()).isTrue();
    assertThat(c.targetBranch()).isEqualTo("refs/heads/master");
    assertThat(c.deleteSourceBranchOnSubmit()).isFalse();
    assertThat(c.isEligibleSource("refs/heads/feature/x")).isTrue();
    assertThat(c.isEligibleSource("refs/heads/feature/a/b/c")).isTrue();
    assertThat(c.isEligibleSource("refs/heads/bugfix/1")).isTrue();
    assertThat(c.isEligibleSource("refs/heads/feature/private/x")).isFalse();
    assertThat(c.isEligibleSource("refs/heads/feature")).isFalse();
    assertThat(c.isEligibleSource("refs/heads/other")).isFalse();
  }

  @Test
  public void hardExclusionsWinOverPatterns() {
    BranchReviewConfig c =
        ConfigParser.parseText(
            """
            [branchReview]
              enabled = true
              targetBranch = refs/heads/main
              sourceBranchPattern = refs/heads/*
            """);
    assertThat(c.isEnabled()).isTrue();
    assertThat(c.isEligibleSource("refs/heads/main")).isFalse();
    assertThat(c.isEligibleSource("refs/heads/topic")).isTrue();
    assertThat(c.isEligibleSource("refs/tags/v1")).isFalse();
    assertThat(c.isEligibleSource("refs/meta/config")).isFalse();
    assertThat(c.isEligibleSource("refs/for/main")).isFalse();
    assertThat(c.isEligibleSource("refs/changes/01/1/1")).isFalse();
    assertThat(c.isEligibleSource("refs/meta/branch-review/sources/aa/bb")).isFalse();
  }

  @Test
  public void rejectsInvalidValues() {
    assertInvalid(VALID.replace("refs/heads/master", "master"), "fully qualified");
    assertInvalid(VALID.replace("refs/heads/master", "refs/heads/*"), "fully qualified");
    assertInvalid(VALID.replace("enabled = true", "enabled = maybe"), "true or false");
    assertInvalid(
        VALID.replace("refs/heads/feature/*", "^refs/heads/feature/.*"), "regular expressions");
    assertInvalid(VALID.replace("refs/heads/feature/*", "refs/heads/*/feature"), "last character");
    assertInvalid(
        VALID.replace("refs/heads/feature/*", "refs/heads/feat?re"), "unsupported character");
    assertInvalid(
        VALID.replace("refs/heads/feature/*", "refs/tags/*"), "must start with refs/heads/");
    assertInvalid(VALID + "  unknownKey = 1\n", "unknown key");
    assertInvalid(VALID + "[other]\n  x = 1\n", "unknown section");
    assertInvalid(VALID + "[branchReview \"sub\"]\n  enabled = true\n", "subsections");
    assertInvalid(
        "[branchReview]\n enabled = true\n targetBranch = refs/heads/master\n", "at least one");
    assertInvalid(
        "[branchReview]\n enabled = true\n sourceBranchPattern = refs/heads/f/*\n", "required");
    assertInvalid("[branchReview\n", "syntax error");
    assertInvalid(VALID + "  enabled = true\n", "at most once");
  }

  @Test
  public void disabledFileStillValidatesPatterns() {
    BranchReviewConfig c =
        ConfigParser.parseText("[branchReview]\n enabled = false\n sourceBranchPattern = ^x\n");
    assertThat(c.status()).isEqualTo(BranchReviewConfig.Status.INVALID);
  }

  @Test
  public void refPatternGrammar() {
    RefPattern exact = RefPattern.parse("refs/heads/release-notes");
    assertThat(exact.matches("refs/heads/release-notes")).isTrue();
    assertThat(exact.matches("refs/heads/release-notes/x")).isFalse();
    RefPattern prefix = RefPattern.parse("refs/heads/feature/*");
    assertThat(prefix.matches("refs/heads/feature/a/b")).isTrue();
    assertThat(prefix.matches("refs/heads/featurex")).isFalse();
    assertThat(prefix.prefix()).isEqualTo("refs/heads/feature/");
    RefPattern bare = RefPattern.parse("refs/heads/feat*");
    assertThat(bare.matches("refs/heads/feature")).isTrue();
    assertThrows(IllegalArgumentException.class, () -> RefPattern.parse(" refs/heads/x"));
    assertThrows(IllegalArgumentException.class, () -> RefPattern.parse("refs/heads/a..b"));
    assertThrows(IllegalArgumentException.class, () -> RefPattern.parse("refs/heads/"));
  }

  private static void assertInvalid(String text, String fragment) {
    BranchReviewConfig c = ConfigParser.parseText(text);
    assertThat(c.status()).isEqualTo(BranchReviewConfig.Status.INVALID);
    assertThat(String.join("\n", c.errors())).contains(fragment);
  }
}
