// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;

/**
 * Strict parser for {@code branch-review.config}. Pure; performs no I/O.
 *
 * <pre>
 * [branchReview]
 *     enabled = true
 *     targetBranch = refs/heads/master        # default route (optional)
 *     sourceBranchPattern = refs/heads/*
 *     excludeBranchPattern = refs/heads/private/*
 *     deleteSourceBranchOnSubmit = false      # default for every route
 *     syncEditsToBranch = true                # default for every route
 * [target "refs/heads/dev"]                   # one section per routed target
 *     sourceBranchPattern = refs/heads/dev/*
 *     deleteSourceBranchOnSubmit = true       # optional override
 * </pre>
 */
public final class ConfigParser {
  public static final String FILE_NAME = "branch-review.config";
  public static final String SECTION = "branchReview";
  public static final String TARGET_SECTION = "target";

  static final String KEY_ENABLED = "enabled";
  static final String KEY_TARGET = "targetBranch";
  static final String KEY_SOURCE = "sourceBranchPattern";
  static final String KEY_EXCLUDE = "excludeBranchPattern";
  static final String KEY_DELETE = "deleteSourceBranchOnSubmit";
  static final String KEY_SYNC_EDITS = "syncEditsToBranch";

  private static final ImmutableSet<String> KNOWN_KEYS =
      ImmutableSet.of(KEY_ENABLED, KEY_TARGET, KEY_SOURCE, KEY_EXCLUDE, KEY_DELETE, KEY_SYNC_EDITS);
  private static final ImmutableSet<String> TARGET_KEYS =
      ImmutableSet.of(KEY_SOURCE, KEY_DELETE, KEY_SYNC_EDITS);

  private ConfigParser() {}

  /** Parses raw file text; {@code null} or empty text means "file absent". */
  public static BranchReviewConfig parseText(String text) {
    if (text == null || text.isBlank()) {
      return BranchReviewConfig.disabled();
    }
    Config cfg = new Config();
    try {
      cfg.fromText(text);
    } catch (ConfigInvalidException e) {
      return BranchReviewConfig.invalid(ImmutableList.of("syntax error: " + e.getMessage()));
    }
    return parse(cfg);
  }

  public static BranchReviewConfig parse(Config cfg) {
    List<String> errors = new ArrayList<>();
    for (String section : cfg.getSections()) {
      if (!section.equals(SECTION) && !section.equals(TARGET_SECTION)) {
        errors.add("unknown section [" + section + "]");
      }
    }
    if (!cfg.getSubsections(SECTION).isEmpty()) {
      errors.add("subsections of [" + SECTION + "] are not supported");
    }
    if (!cfg.getNames(TARGET_SECTION).isEmpty()) {
      errors.add("[" + TARGET_SECTION + "] needs the target branch: [target \"refs/heads/...\"]");
    }
    checkKeys(cfg, SECTION, null, KNOWN_KEYS, errors);
    for (String single : List.of(KEY_ENABLED, KEY_TARGET, KEY_DELETE, KEY_SYNC_EDITS)) {
      if (cfg.getStringList(SECTION, null, single).length > 1) {
        errors.add(SECTION + "." + single + " must be set at most once");
      }
    }

    Boolean enabled = parseBoolean(cfg, SECTION, null, KEY_ENABLED, errors).orElse(false);
    boolean delete = parseBoolean(cfg, SECTION, null, KEY_DELETE, errors).orElse(false);
    boolean syncEdits = parseBoolean(cfg, SECTION, null, KEY_SYNC_EDITS, errors).orElse(true);
    // Patterns and routes are validated even when disabled so that a file can be reviewed first.
    ImmutableList<RefPattern> sources = parsePatterns(cfg, SECTION, null, KEY_SOURCE, errors);
    ImmutableList<RefPattern> excludes = parsePatterns(cfg, SECTION, null, KEY_EXCLUDE, errors);
    List<BranchReviewConfig.Route> routes = new ArrayList<>();
    for (String target : new TreeSet<>(cfg.getSubsections(TARGET_SECTION))) {
      String where = TARGET_SECTION + " \"" + target + "\"";
      checkTarget(where, target, errors);
      checkKeys(cfg, TARGET_SECTION, target, TARGET_KEYS, errors);
      for (String single : List.of(KEY_DELETE, KEY_SYNC_EDITS)) {
        if (cfg.getStringList(TARGET_SECTION, target, single).length > 1) {
          errors.add(where + "." + single + " must be set at most once");
        }
      }
      ImmutableList<RefPattern> patterns =
          parsePatterns(cfg, TARGET_SECTION, target, KEY_SOURCE, errors);
      if (patterns.isEmpty() && cfg.getStringList(TARGET_SECTION, target, KEY_SOURCE).length == 0) {
        errors.add("[" + where + "] needs at least one " + KEY_SOURCE);
      }
      routes.add(
          new BranchReviewConfig.Route(
              target,
              patterns,
              parseBoolean(cfg, TARGET_SECTION, target, KEY_DELETE, errors),
              parseBoolean(cfg, TARGET_SECTION, target, KEY_SYNC_EDITS, errors)));
    }
    if (!errors.isEmpty()) {
      return BranchReviewConfig.invalid(ImmutableList.copyOf(errors));
    }
    if (!enabled) {
      return BranchReviewConfig.disabled();
    }

    String target = cfg.getString(SECTION, null, KEY_TARGET);
    if (target != null && !target.isEmpty()) {
      checkTarget(SECTION + "." + KEY_TARGET, target, errors);
      if (sources.isEmpty()) {
        errors.add(
            "at least one " + SECTION + "." + KEY_SOURCE + " is required with " + KEY_TARGET);
      }
    } else {
      target = null;
      if (!sources.isEmpty()) {
        errors.add(
            SECTION
                + "."
                + KEY_TARGET
                + " is required for the "
                + SECTION
                + "."
                + KEY_SOURCE
                + " patterns");
      } else if (routes.isEmpty()) {
        errors.add(
            "nothing to review: set "
                + SECTION
                + "."
                + KEY_TARGET
                + " with "
                + KEY_SOURCE
                + " patterns, or add [target \"refs/heads/...\"] sections");
      }
    }

    // A pattern naming a target exactly is a mistake: targets are never sources.
    ImmutableSet.Builder<String> targetsBuilder = ImmutableSet.builder();
    if (target != null) {
      targetsBuilder.add(target);
    }
    routes.forEach(r -> targetsBuilder.add(r.target()));
    ImmutableSet<String> targets = targetsBuilder.build();
    List<RefPattern> all = new ArrayList<>(sources);
    routes.forEach(r -> all.addAll(r.sourcePatterns()));
    for (RefPattern p : all) {
      if (!p.isWildcard() && targets.contains(p.toString())) {
        errors.add(KEY_SOURCE + " " + p + " names a target branch");
      }
      // Git cannot store refs/heads/dev and refs/heads/dev/x at the same time (a ref name is also
      // a path), so such source branches could never be pushed while the target exists.
      for (String t : targets) {
        if (p.toString().startsWith(t + "/")) {
          errors.add(
              KEY_SOURCE
                  + " "
                  + p
                  + " lies under the target branch "
                  + t
                  + ": Git cannot have both "
                  + t
                  + " and branches below it; use e.g. "
                  + t
                  + "-* or a different target name");
        }
      }
    }

    // Overlapping routes are resolved by specificity at match time; the only undecidable case is
    // the very same pattern routed to two targets.
    Map<RefPattern, String> routedBy = new HashMap<>();
    for (BranchReviewConfig.Route r : routes) {
      for (RefPattern p : r.sourcePatterns()) {
        String other = routedBy.putIfAbsent(p, r.target());
        if (other != null && !other.equals(r.target())) {
          errors.add(KEY_SOURCE + " " + p + " is routed to both " + other + " and " + r.target());
        }
      }
    }
    if (!errors.isEmpty()) {
      return BranchReviewConfig.invalid(ImmutableList.copyOf(errors));
    }
    return new BranchReviewConfig(
        BranchReviewConfig.Status.ENABLED,
        target,
        sources,
        excludes,
        ImmutableList.copyOf(routes),
        delete,
        syncEdits,
        ImmutableList.of());
  }

  private static void checkTarget(String where, String target, List<String> errors) {
    if (!target.startsWith("refs/heads/")
        || target.contains("*")
        || !Repository.isValidRefName(target)
        || target.startsWith(BranchReviewConfig.STATE_NAMESPACE)) {
      errors.add(where + " must be a fully qualified branch such as refs/heads/master: " + target);
    }
  }

  private static void checkKeys(
      Config cfg, String section, String sub, Set<String> known, List<String> errors) {
    for (String name : cfg.getNames(section, sub)) {
      if (known.stream().noneMatch(k -> k.equalsIgnoreCase(name))) {
        errors.add(
            "unknown key " + (sub == null ? section : section + " \"" + sub + "\"") + "." + name);
      }
    }
  }

  private static Optional<Boolean> parseBoolean(
      Config cfg, String section, String sub, String key, List<String> errors) {
    if (cfg.getNames(section, sub).stream().noneMatch(n -> n.equalsIgnoreCase(key))) {
      return Optional.empty();
    }
    try {
      return Optional.of(cfg.getBoolean(section, sub, key, false));
    } catch (IllegalArgumentException e) {
      errors.add(
          (sub == null ? section : section + " \"" + sub + "\"")
              + "."
              + key
              + " must be true or false");
      return Optional.empty();
    }
  }

  private static ImmutableList<RefPattern> parsePatterns(
      Config cfg, String section, String sub, String key, List<String> errors) {
    ImmutableList.Builder<RefPattern> out = ImmutableList.builder();
    for (String raw : cfg.getStringList(section, sub, key)) {
      try {
        out.add(RefPattern.parse(raw));
      } catch (IllegalArgumentException e) {
        errors.add(
            (sub == null ? section : section + " \"" + sub + "\"")
                + "."
                + key
                + " '"
                + raw
                + "': "
                + e.getMessage());
      }
    }
    return out.build().stream().distinct().collect(ImmutableList.toImmutableList());
  }
}
