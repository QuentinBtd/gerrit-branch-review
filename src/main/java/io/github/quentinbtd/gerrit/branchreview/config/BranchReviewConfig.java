// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.config;

import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * Parsed content of {@code branch-review.config} on a project's {@code refs/meta/config}.
 *
 * <p>Instances are immutable. A disabled configuration carries no patterns; an invalid one carries
 * the list of problems and must never be used to mutate anything.
 */
public record BranchReviewConfig(
    Status status,
    String targetBranch,
    ImmutableList<RefPattern> sourcePatterns,
    ImmutableList<RefPattern> excludePatterns,
    boolean deleteSourceBranchOnSubmit,
    ImmutableList<String> errors) {

  /** Namespace that holds plugin state; never eligible and never writable by users. */
  public static final String STATE_NAMESPACE = "refs/meta/branch-review/";

  public enum Status {
    DISABLED,
    ENABLED,
    INVALID
  }

  public static BranchReviewConfig disabled() {
    return new BranchReviewConfig(
        Status.DISABLED, null, ImmutableList.of(), ImmutableList.of(), false, ImmutableList.of());
  }

  public static BranchReviewConfig invalid(ImmutableList<String> errors) {
    return new BranchReviewConfig(
        Status.INVALID, null, ImmutableList.of(), ImmutableList.of(), false, errors);
  }

  public boolean isEnabled() {
    return status == Status.ENABLED;
  }

  /**
   * Whether {@code refName} is a source branch managed by this configuration. Refs outside {@code
   * refs/heads/}, the target branch and plugin metadata are never eligible, whatever the patterns
   * say.
   */
  public boolean isEligibleSource(String refName) {
    if (!isEnabled() || !isOrdinaryBranch(refName) || refName.equals(targetBranch)) {
      return false;
    }
    if (excludePatterns.stream().anyMatch(p -> p.matches(refName))) {
      return false;
    }
    return sourcePatterns.stream().anyMatch(p -> p.matches(refName));
  }

  /** The first source pattern matching {@code refName}, used for title generation. */
  public Optional<RefPattern> matchingPattern(String refName) {
    return sourcePatterns.stream().filter(p -> p.matches(refName)).findFirst();
  }

  public static boolean isOrdinaryBranch(String refName) {
    return refName != null
        && refName.startsWith("refs/heads/")
        && refName.length() > "refs/heads/".length()
        && !refName.startsWith(STATE_NAMESPACE);
  }
}
