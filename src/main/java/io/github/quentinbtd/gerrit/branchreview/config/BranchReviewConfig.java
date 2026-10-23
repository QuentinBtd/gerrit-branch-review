// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/**
 * Parsed content of {@code branch-review.config} on a project's {@code refs/meta/config}.
 *
 * <p>A source branch is routed to a target branch: by the {@code [target "<branch>"]} section whose
 * patterns match it, otherwise by the default route of {@code [branchReview]} (its {@code
 * targetBranch} and {@code sourceBranchPattern}s), if any. Exclusions apply to every route, and
 * routes never overlap (rejected by the parser), so the target of a branch is unambiguous.
 *
 * <p>Instances are immutable. A disabled configuration carries no routes; an invalid one carries
 * the list of problems and must never be used to mutate anything.
 */
public record BranchReviewConfig(
    Status status,
    /** Default target, or {@code null} when only {@code [target]} sections are configured. */
    String targetBranch,
    /** Sources of the default route. */
    ImmutableList<RefPattern> sourcePatterns,
    ImmutableList<RefPattern> excludePatterns,
    ImmutableList<Route> routes,
    boolean deleteSourceBranchOnSubmit,
    boolean syncEditsToBranch,
    ImmutableList<String> errors) {

  /** Namespace that holds plugin state; never eligible and never writable by users. */
  public static final String STATE_NAMESPACE = "refs/meta/branch-review/";

  public enum Status {
    DISABLED,
    ENABLED,
    INVALID
  }

  /**
   * A {@code [target "<branch>"]} section: its source patterns and optional per-route overrides of
   * {@code deleteSourceBranchOnSubmit} and {@code syncEditsToBranch}.
   */
  public record Route(
      String target,
      ImmutableList<RefPattern> sourcePatterns,
      Optional<Boolean> deleteSourceBranchOnSubmit,
      Optional<Boolean> syncEditsToBranch) {}

  public static BranchReviewConfig disabled() {
    return new BranchReviewConfig(
        Status.DISABLED,
        null,
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        false,
        false,
        ImmutableList.of());
  }

  public static BranchReviewConfig invalid(ImmutableList<String> errors) {
    return new BranchReviewConfig(
        Status.INVALID,
        null,
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        false,
        false,
        errors);
  }

  public boolean isEnabled() {
    return status == Status.ENABLED;
  }

  /** Every configured target branch (default and routes). */
  public ImmutableSet<String> targets() {
    ImmutableSet.Builder<String> b = ImmutableSet.builder();
    if (targetBranch != null) {
      b.add(targetBranch);
    }
    routes.forEach(r -> b.add(r.target()));
    return b.build();
  }

  /** The route of a branch and the pattern that selected it. */
  private record Match(Route route, RefPattern pattern) {}

  /**
   * The route of {@code refName}: empty when it is not a managed source branch. Refs outside {@code
   * refs/heads/}, target branches and plugin metadata are never sources, whatever the patterns say.
   * Among the {@code [target]} patterns matching the branch the most specific one wins ({@link
   * RefPattern#compareSpecificity}); the default route applies only when none matches.
   */
  private Optional<Match> match(String refName) {
    if (!isEnabled() || !isOrdinaryBranch(refName) || targets().contains(refName)) {
      return Optional.empty();
    }
    if (excludePatterns.stream().anyMatch(p -> p.matches(refName))) {
      return Optional.empty();
    }
    Match best = null;
    for (Route r : routes) {
      for (RefPattern p : r.sourcePatterns()) {
        if (p.matches(refName) && (best == null || p.compareSpecificity(best.pattern()) > 0)) {
          best = new Match(r, p);
        }
      }
    }
    if (best == null && targetBranch != null) {
      Route fallback = new Route(targetBranch, sourcePatterns, Optional.empty(), Optional.empty());
      for (RefPattern p : sourcePatterns) {
        if (p.matches(refName) && (best == null || p.compareSpecificity(best.pattern()) > 0)) {
          best = new Match(fallback, p);
        }
      }
    }
    return Optional.ofNullable(best);
  }

  private Optional<Route> routeOf(String refName) {
    return match(refName).map(Match::route);
  }

  /** Whether {@code refName} is a source branch managed by this configuration. */
  public boolean isEligibleSource(String refName) {
    return routeOf(refName).isPresent();
  }

  /** The target branch of the reviews of {@code refName}, if it is a managed source branch. */
  public Optional<String> targetFor(String refName) {
    return routeOf(refName).map(Route::target);
  }

  /** The pattern that routed {@code refName}, used for title generation. */
  public Optional<RefPattern> matchingPattern(String refName) {
    return match(refName).map(Match::pattern);
  }

  /** {@code deleteSourceBranchOnSubmit} for {@code refName}: route override, else the default. */
  public boolean deleteSourceBranchOnSubmit(String refName) {
    return routeOf(refName)
        .flatMap(Route::deleteSourceBranchOnSubmit)
        .orElse(deleteSourceBranchOnSubmit);
  }

  /** {@code syncEditsToBranch} for {@code refName}: route override, else the default. */
  public boolean syncEditsToBranch(String refName) {
    return routeOf(refName).flatMap(Route::syncEditsToBranch).orElse(syncEditsToBranch);
  }

  public static boolean isOrdinaryBranch(String refName) {
    return refName != null
        && refName.startsWith("refs/heads/")
        && refName.length() > "refs/heads/".length()
        && !refName.startsWith(STATE_NAMESPACE);
  }
}
