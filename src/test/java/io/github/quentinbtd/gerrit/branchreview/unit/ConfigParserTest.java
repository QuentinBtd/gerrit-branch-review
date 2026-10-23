// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.unit;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import io.github.quentinbtd.gerrit.branchreview.config.ConfigParser;
import io.github.quentinbtd.gerrit.branchreview.config.RefPattern;
import java.util.ArrayList;
import java.util.List;
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
    assertThat(c.syncEditsToBranch()).isTrue();
    assertThat(ConfigParser.parseText(VALID + "  syncEditsToBranch = false\n").syncEditsToBranch())
        .isFalse();
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
        VALID + "  syncEditsToBranch = sometimes\n", "syncEditsToBranch must be true or false");
    assertInvalid(
        VALID.replace("refs/heads/feature/*", "^refs/heads/feature/.*"), "regular expressions");
    assertInvalid(
        VALID.replace("refs/heads/feature/*", "refs/heads/fe*ture/x"), "must be a whole segment");
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
    assertThat(prefix.remainderOf("refs/heads/feature/a/b")).isEqualTo("a/b");
    RefPattern bare = RefPattern.parse("refs/heads/feat*");
    assertThat(bare.matches("refs/heads/feature")).isTrue();
    assertThrows(IllegalArgumentException.class, () -> RefPattern.parse(" refs/heads/x"));
    assertThrows(IllegalArgumentException.class, () -> RefPattern.parse("refs/heads/a..b"));
    assertThrows(IllegalArgumentException.class, () -> RefPattern.parse("refs/heads/"));
  }

  @Test
  public void midPathWildcardMatchesExactlyOneSegment() {
    RefPattern p = RefPattern.parse("refs/heads/*/feat/*");
    assertThat(p.matches("refs/heads/alice/feat/login")).isTrue();
    assertThat(p.matches("refs/heads/alice/feat/a/b")).isTrue();
    assertThat(p.remainderOf("refs/heads/alice/feat/a/b")).isEqualTo("a/b");
    assertThat(p.matches("refs/heads/a/b/feat/x")).isFalse();
    assertThat(p.matches("refs/heads/feat/x")).isFalse();
    assertThat(p.matches("refs/heads/alice/feat")).isFalse();
    RefPattern exact = RefPattern.parse("refs/heads/*/release");
    assertThat(exact.matches("refs/heads/team/release")).isTrue();
    assertThat(exact.matches("refs/heads/team/release/x")).isFalse();
    assertThat(exact.remainderOf("refs/heads/team/release")).isEmpty();
    for (String bad :
        new String[] {
          "refs/heads/fe*t/x", "refs/heads/**/x", "refs/heads/a*/x", "refs/*/x", "refs/heads/a/fe*t"
        }) {
      assertThrows(IllegalArgumentException.class, () -> RefPattern.parse(bad));
    }
  }

  @Test
  public void mostSpecificPatternWins() {
    assertMoreSpecific("refs/heads/run/*", "refs/heads/*/feat/*");
    assertMoreSpecific("refs/heads/dev/*", "refs/heads/*/chore/*");
    assertMoreSpecific("refs/heads/run/*", "refs/heads/*");
    assertMoreSpecific("refs/heads/feature/*", "refs/heads/feat*");
    assertMoreSpecific("refs/heads/xy*", "refs/heads/x*");
    assertMoreSpecific("refs/heads/a/b", "refs/heads/*/b");
    // At the first differing segment, a literal prefix beats a whole-segment "*".
    assertMoreSpecific("refs/heads/feat*", "refs/heads/*/feat/*");
    assertMoreSpecific("refs/heads/*/feat/*", "refs/heads/*");
    assertThat(
            RefPattern.parse("refs/heads/a/*")
                .compareSpecificity(RefPattern.parse("refs/heads/a/*")))
        .isEqualTo(0);
  }

  private static void assertMoreSpecific(String a, String b) {
    assertThat(RefPattern.parse(a).compareSpecificity(RefPattern.parse(b))).isGreaterThan(0);
    assertThat(RefPattern.parse(b).compareSpecificity(RefPattern.parse(a))).isLessThan(0);
  }

  private static final String ROUTED =
      """
      [branchReview]
        enabled = true
        targetBranch = refs/heads/master
        sourceBranchPattern = refs/heads/*
        excludeBranchPattern = refs/heads/private/*
        deleteSourceBranchOnSubmit = false
      [target "refs/heads/develop"]
        sourceBranchPattern = refs/heads/dev/*
        sourceBranchPattern = refs/heads/*/feat/*
        sourceBranchPattern = refs/heads/*/chore/*
      [target "refs/heads/runtime"]
        sourceBranchPattern = refs/heads/run/*
        deleteSourceBranchOnSubmit = true
        syncEditsToBranch = false
      """;

  @Test
  public void routesSendBranchesToTheirTargetWithDefaultFallback() {
    BranchReviewConfig c = ConfigParser.parseText(ROUTED);
    assertThat(c.errors()).isEmpty();
    assertThat(c.targets())
        .containsExactly("refs/heads/master", "refs/heads/develop", "refs/heads/runtime");
    assertThat(c.targetFor("refs/heads/dev/login")).hasValue("refs/heads/develop");
    assertThat(c.targetFor("refs/heads/alice/feat/login")).hasValue("refs/heads/develop");
    assertThat(c.targetFor("refs/heads/team/chore/deps")).hasValue("refs/heads/develop");
    assertThat(c.targetFor("refs/heads/run/fix-42")).hasValue("refs/heads/runtime");
    assertThat(c.targetFor("refs/heads/a/b/feat/x")).hasValue("refs/heads/master");
    assertThat(c.targetFor("refs/heads/feature/x")).hasValue("refs/heads/master");
    // Exclusions win, and targets are never sources.
    assertThat(c.targetFor("refs/heads/private/x")).isEmpty();
    assertThat(c.targetFor("refs/heads/develop")).isEmpty();
    assertThat(c.targetFor("refs/heads/runtime")).isEmpty();
    assertThat(c.targetFor("refs/heads/master")).isEmpty();
    // Per-route overrides, defaults elsewhere.
    assertThat(c.deleteSourceBranchOnSubmit("refs/heads/run/x")).isTrue();
    assertThat(c.syncEditsToBranch("refs/heads/run/x")).isFalse();
    assertThat(c.deleteSourceBranchOnSubmit("refs/heads/dev/x")).isFalse();
    assertThat(c.syncEditsToBranch("refs/heads/dev/x")).isTrue();
    assertThat(c.matchingPattern("refs/heads/alice/feat/login").map(Object::toString))
        .hasValue("refs/heads/*/feat/*");
  }

  @Test
  public void routesWithoutADefault() {
    BranchReviewConfig c =
        ConfigParser.parseText(
            """
            [branchReview]
              enabled = true
            [target "refs/heads/develop"]
              sourceBranchPattern = refs/heads/dev/*
            """);
    assertThat(c.isEnabled()).isTrue();
    assertThat(c.targetBranch()).isNull();
    assertThat(c.targetFor("refs/heads/dev/x")).hasValue("refs/heads/develop");
    assertThat(c.targetFor("refs/heads/feature/x")).isEmpty();
  }

  @Test
  public void overlappingRoutesPickTheMostSpecificPattern() {
    BranchReviewConfig c = ConfigParser.parseText(ROUTED);
    // run/* (literal "run") beats */feat/* and */chore/* (mid-path "*").
    assertThat(c.targetFor("refs/heads/run/feat/x")).hasValue("refs/heads/runtime");
    assertThat(c.targetFor("refs/heads/run/chore/x")).hasValue("refs/heads/runtime");
    assertThat(c.matchingPattern("refs/heads/run/feat/x").map(Object::toString))
        .hasValue("refs/heads/run/*");
    // dev/* beats */feat/* within the same route as well.
    assertThat(c.matchingPattern("refs/heads/dev/feat/x").map(Object::toString))
        .hasValue("refs/heads/dev/*");
    // The default route is only a fallback, even when its pattern would be more specific.
    BranchReviewConfig fallback =
        ConfigParser.parseText(
            ROUTED.replace(
                "sourceBranchPattern = refs/heads/*\n",
                "sourceBranchPattern = refs/heads/x/feat/*\n"));
    assertThat(fallback.targetFor("refs/heads/x/feat/y")).hasValue("refs/heads/develop");
  }

  @Test
  public void invalidRoutesAreRejected() {
    assertInvalid(
        ROUTED.replace(
            "sourceBranchPattern = refs/heads/run/*", "sourceBranchPattern = refs/heads/dev/*"),
        "sourceBranchPattern refs/heads/dev/* is routed to both refs/heads/develop and"
            + " refs/heads/runtime");
    assertInvalid(
        ROUTED.replace("[target \"refs/heads/runtime\"]", "[target \"runtime\"]"),
        "fully qualified");
    assertInvalid(
        ROUTED + "[target \"refs/heads/qa\"]\n  deleteSourceBranchOnSubmit = true\n",
        "needs at least one");
    assertInvalid(
        ROUTED + "[target \"refs/heads/qa\"]\n  targetBranch = refs/heads/x\n",
        "unknown key target");
    assertInvalid(
        ROUTED + "[target]\n  sourceBranchPattern = refs/heads/qa/*\n", "needs the target branch");
    assertInvalid(
        ROUTED.replace("syncEditsToBranch = false", "syncEditsToBranch = never"),
        "must be true or false");
    assertInvalid(
        ROUTED + "[target \"refs/heads/qa\"]\n  sourceBranchPattern = refs/heads/develop\n",
        "names a target branch");
    assertInvalid(
        ROUTED + "[target \"refs/heads/qa\"]\n  sourceBranchPattern = refs/heads/q*a/x\n",
        "whole segment");
    assertInvalid("[branchReview]\n enabled = true\n", "nothing to review");
    assertInvalid(
        "[branchReview]\n enabled = true\n[target \"refs/heads/dev\"]\n"
            + " sourceBranchPattern = refs/heads/dev/*\n",
        "lies under the target branch refs/heads/dev");
  }

  @Test
  public void workInProgressDefaultsToTrueAndCanBeOverriddenPerRoute() {
    BranchReviewConfig c = ConfigParser.parseText(VALID);
    assertThat(c.workInProgress("refs/heads/feature/x")).isTrue();
    BranchReviewConfig ready =
        ConfigParser.parseText(
            VALID
                + "  workInProgress = false\n"
                + "[target \"refs/heads/stable\"]\n"
                + "  sourceBranchPattern = refs/heads/stable-*\n"
                + "  workInProgress = true\n");
    assertThat(ready.errors()).isEmpty();
    assertThat(ready.workInProgress("refs/heads/feature/x")).isFalse();
    assertThat(ready.workInProgress("refs/heads/stable-fix")).isTrue();
    assertInvalid(VALID + "  workInProgress = maybe\n", "workInProgress must be true or false");
    assertInvalid(VALID + "  workInProgress = true\n  workInProgress = false\n", "at most once");
  }

  private static BranchReviewConfig merge(String... rootFirst) {
    List<ConfigParser.Layer> layers = new ArrayList<>();
    for (int i = 0; i < rootFirst.length; i++) {
      layers.add(ConfigParser.parseLayer(rootFirst[i], "p" + i));
    }
    return ConfigParser.merge(layers);
  }

  @Test
  public void parentEnablesChildrenWithoutFiles() {
    BranchReviewConfig c = merge(VALID, null, "");
    assertThat(c.errors()).isEmpty();
    assertThat(c.isEnabled()).isTrue();
    assertThat(c.targetFor("refs/heads/feature/x")).hasValue("refs/heads/master");
  }

  @Test
  public void childDisablesInheritedConfig() {
    assertThat(merge(VALID, "[branchReview]\n enabled = false\n").status())
        .isEqualTo(BranchReviewConfig.Status.DISABLED);
    // ... and a grandchild can enable it again.
    assertThat(
            merge(VALID, "[branchReview]\n enabled = false\n", "[branchReview]\n enabled = true\n")
                .isEnabled())
        .isTrue();
  }

  @Test
  public void childReplacesKeysAndLists() {
    BranchReviewConfig c =
        merge(
            VALID,
            """
            [branchReview]
              targetBranch = refs/heads/main
              sourceBranchPattern = refs/heads/topic/*
              deleteSourceBranchOnSubmit = true
            """);
    assertThat(c.errors()).isEmpty();
    assertThat(c.targetFor("refs/heads/topic/x")).hasValue("refs/heads/main");
    // The list is replaced, not appended to.
    assertThat(c.isEligibleSource("refs/heads/feature/x")).isFalse();
    assertThat(c.isEligibleSource("refs/heads/bugfix/x")).isFalse();
    assertThat(c.deleteSourceBranchOnSubmit()).isTrue();
    // Keys the child does not set are inherited.
    assertThat(c.excludePatterns())
        .containsExactly(RefPattern.parse("refs/heads/feature/private/*"));
    assertThat(merge(VALID, "[branchReview]\n syncEditsToBranch = false\n").targetBranch())
        .isEqualTo("refs/heads/master");
  }

  @Test
  public void emptyValueClearsInheritedValue() {
    BranchReviewConfig c = merge(VALID, "[branchReview]\n excludeBranchPattern =\n");
    assertThat(c.errors()).isEmpty();
    assertThat(c.isEligibleSource("refs/heads/feature/private/x")).isTrue();

    BranchReviewConfig routedOnly =
        merge(
            VALID,
            """
            [branchReview]
              targetBranch =
              sourceBranchPattern =
            [target "refs/heads/dev"]
              sourceBranchPattern = refs/heads/dev-*
            """);
    assertThat(routedOnly.errors()).isEmpty();
    assertThat(routedOnly.targetBranch()).isNull();
    assertThat(routedOnly.targetFor("refs/heads/feature/x")).isEmpty();
    assertThat(routedOnly.targetFor("refs/heads/dev-x")).hasValue("refs/heads/dev");
  }

  @Test
  public void routesMergeByTargetKeyByKey() {
    String parent =
        """
        [branchReview]
          enabled = true
        [target "refs/heads/dev"]
          sourceBranchPattern = refs/heads/dev-*
          deleteSourceBranchOnSubmit = true
        [target "refs/heads/run"]
          sourceBranchPattern = refs/heads/run-*
        """;
    BranchReviewConfig c =
        merge(
            parent,
            """
            [target "refs/heads/dev"]
              workInProgress = false
            [target "refs/heads/run"]
              enabled = false
            [target "refs/heads/stable"]
              sourceBranchPattern = refs/heads/stable-*
            """);
    assertThat(c.errors()).isEmpty();
    assertThat(c.targets()).containsExactly("refs/heads/dev", "refs/heads/stable");
    assertThat(c.targetFor("refs/heads/dev-x")).hasValue("refs/heads/dev");
    assertThat(c.deleteSourceBranchOnSubmit("refs/heads/dev-x")).isTrue();
    assertThat(c.workInProgress("refs/heads/dev-x")).isFalse();
    assertThat(c.targetFor("refs/heads/run-x")).isEmpty();
    assertThat(c.targetFor("refs/heads/stable-x")).hasValue("refs/heads/stable");
  }

  @Test
  public void effectiveRulesApplyToTheMergedConfig() {
    // A child's route repeating a pattern of an inherited route is caught after merging.
    BranchReviewConfig c =
        merge(
            "[branchReview]\n enabled = true\n[target \"refs/heads/dev\"]\n"
                + " sourceBranchPattern = refs/heads/x-*\n",
            "[target \"refs/heads/run\"]\n sourceBranchPattern = refs/heads/x-*\n");
    assertThat(c.status()).isEqualTo(BranchReviewConfig.Status.INVALID);
    assertThat(String.join("\n", c.errors())).contains("is routed to both");
    // A route without patterns, from a child that only sets an override, is invalid.
    assertThat(
            String.join(
                "\n",
                merge(VALID, "[target \"refs/heads/dev\"]\n workInProgress = false\n").errors()))
        .contains("needs at least one sourceBranchPattern");
  }

  @Test
  public void fileErrorsNameTheirProject() {
    BranchReviewConfig c = merge(VALID, "[branchReview]\n bogus = 1\n");
    assertThat(c.status()).isEqualTo(BranchReviewConfig.Status.INVALID);
    assertThat(String.join("\n", c.errors())).contains("in p1: ");
    // A single file is not attributed, whatever the number of ancestors without one.
    assertThat(String.join("\n", merge(null, "", "[branchReview]\n bogus = 1\n").errors()))
        .doesNotContain("in p2");
  }

  private static void assertInvalid(String text, String fragment) {
    BranchReviewConfig c = ConfigParser.parseText(text);
    assertThat(c.status()).isEqualTo(BranchReviewConfig.Status.INVALID);
    assertThat(String.join("\n", c.errors())).contains(fragment);
  }
}
