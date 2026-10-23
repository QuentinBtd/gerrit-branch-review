// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
 *     workInProgress = true                   # new Changes start WIP (default for every route)
 * [target "refs/heads/dev"]                   # one section per routed target
 *     sourceBranchPattern = refs/heads/dev-*
 *     deleteSourceBranchOnSubmit = true       # optional override
 * </pre>
 *
 * <p>A project's effective configuration is the files of its parent chain merged from the root
 * (All-Projects) down to the project ({@link #merge}): every key set in a lower file replaces the
 * inherited value (lists included), {@code [target]} sections are merged by target branch key by
 * key, and {@code enabled = false} in a {@code [target]} section removes an inherited route. An
 * empty value ({@code targetBranch =}, {@code excludeBranchPattern =}) clears an inherited value.
 * Each file is checked on its own ({@link #parseLayer}); the rules that need the whole picture
 * (required keys, overlapping routes, ...) apply to the effective configuration.
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
  static final String KEY_WIP = "workInProgress";

  private static final ImmutableSet<String> KNOWN_KEYS =
      ImmutableSet.of(
          KEY_ENABLED, KEY_TARGET, KEY_SOURCE, KEY_EXCLUDE, KEY_DELETE, KEY_SYNC_EDITS, KEY_WIP);
  private static final ImmutableSet<String> TARGET_KEYS =
      ImmutableSet.of(KEY_ENABLED, KEY_SOURCE, KEY_DELETE, KEY_SYNC_EDITS, KEY_WIP);

  /**
   * The settings of one file. An empty {@link Optional} means "not set in this file": the value is
   * inherited from the files above.
   */
  public record Layer(
      String source,
      Optional<Boolean> enabled,
      Optional<String> targetBranch,
      Optional<ImmutableList<RefPattern>> sourcePatterns,
      Optional<ImmutableList<RefPattern>> excludePatterns,
      Optional<Boolean> deleteSourceBranchOnSubmit,
      Optional<Boolean> syncEditsToBranch,
      Optional<Boolean> workInProgress,
      ImmutableMap<String, LayerRoute> targets,
      ImmutableList<String> errors) {
    static Layer empty(String source, List<String> errors) {
      return new Layer(
          source,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          ImmutableMap.of(),
          ImmutableList.copyOf(errors));
    }

    /** Whether this file sets nothing (absent, blank, or only comments). */
    public boolean isEmpty() {
      return errors.isEmpty()
          && targets.isEmpty()
          && enabled.isEmpty()
          && targetBranch.isEmpty()
          && sourcePatterns.isEmpty()
          && excludePatterns.isEmpty()
          && deleteSourceBranchOnSubmit.isEmpty()
          && syncEditsToBranch.isEmpty()
          && workInProgress.isEmpty();
    }
  }

  /** A {@code [target]} section of one file; empty values are inherited. */
  public record LayerRoute(
      Optional<Boolean> enabled,
      Optional<ImmutableList<RefPattern>> sourcePatterns,
      Optional<Boolean> deleteSourceBranchOnSubmit,
      Optional<Boolean> syncEditsToBranch,
      Optional<Boolean> workInProgress) {
    static final LayerRoute EMPTY =
        new LayerRoute(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    /** This section's values where set, the inherited section's values otherwise. */
    LayerRoute over(LayerRoute inherited) {
      return new LayerRoute(
          enabled.or(inherited::enabled),
          sourcePatterns.or(inherited::sourcePatterns),
          deleteSourceBranchOnSubmit.or(inherited::deleteSourceBranchOnSubmit),
          syncEditsToBranch.or(inherited::syncEditsToBranch),
          workInProgress.or(inherited::workInProgress));
    }
  }

  private ConfigParser() {}

  /** Parses a single file; {@code null} or empty text means "file absent" (disabled). */
  public static BranchReviewConfig parseText(String text) {
    return merge(List.of(parseLayer(text, "")));
  }

  /**
   * Parses one file into a {@link Layer}, checking everything that does not depend on other files.
   * {@code source} names the file's project in error messages (empty for a single file).
   */
  public static Layer parseLayer(String text, String source) {
    if (text == null || text.isBlank()) {
      return Layer.empty(source, List.of());
    }
    Config cfg = new Config();
    try {
      cfg.fromText(text);
    } catch (ConfigInvalidException e) {
      return Layer.empty(source, List.of("syntax error: " + e.getMessage()));
    }
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
    for (String single : List.of(KEY_ENABLED, KEY_TARGET, KEY_DELETE, KEY_SYNC_EDITS, KEY_WIP)) {
      if (cfg.getStringList(SECTION, null, single).length > 1) {
        errors.add(SECTION + "." + single + " must be set at most once");
      }
    }
    Optional<String> target = Optional.empty();
    if (isSet(cfg, SECTION, null, KEY_TARGET)) {
      String t = cfg.getString(SECTION, null, KEY_TARGET);
      t = t == null ? "" : t.strip();
      if (!t.isEmpty()) {
        checkTarget(SECTION + "." + KEY_TARGET, t, errors);
      }
      target = Optional.of(t);
    }
    Map<String, LayerRoute> targets = new TreeMap<>();
    for (String t : new TreeSet<>(cfg.getSubsections(TARGET_SECTION))) {
      String where = TARGET_SECTION + " \"" + t + "\"";
      checkTarget(where, t, errors);
      checkKeys(cfg, TARGET_SECTION, t, TARGET_KEYS, errors);
      for (String single : List.of(KEY_ENABLED, KEY_DELETE, KEY_SYNC_EDITS, KEY_WIP)) {
        if (cfg.getStringList(TARGET_SECTION, t, single).length > 1) {
          errors.add(where + "." + single + " must be set at most once");
        }
      }
      targets.put(
          t,
          new LayerRoute(
              parseBoolean(cfg, TARGET_SECTION, t, KEY_ENABLED, errors),
              parsePatterns(cfg, TARGET_SECTION, t, KEY_SOURCE, errors),
              parseBoolean(cfg, TARGET_SECTION, t, KEY_DELETE, errors),
              parseBoolean(cfg, TARGET_SECTION, t, KEY_SYNC_EDITS, errors),
              parseBoolean(cfg, TARGET_SECTION, t, KEY_WIP, errors)));
    }
    return new Layer(
        source,
        parseBoolean(cfg, SECTION, null, KEY_ENABLED, errors),
        target,
        parsePatterns(cfg, SECTION, null, KEY_SOURCE, errors),
        parsePatterns(cfg, SECTION, null, KEY_EXCLUDE, errors),
        parseBoolean(cfg, SECTION, null, KEY_DELETE, errors),
        parseBoolean(cfg, SECTION, null, KEY_SYNC_EDITS, errors),
        parseBoolean(cfg, SECTION, null, KEY_WIP, errors),
        ImmutableMap.copyOf(targets),
        ImmutableList.copyOf(errors));
  }

  /**
   * Merges the files of a parent chain, root first, into the effective configuration and checks it.
   * Errors of a file are reported with its project name when several files contribute.
   */
  public static BranchReviewConfig merge(List<Layer> rootFirst) {
    List<String> errors = new ArrayList<>();
    boolean attribute = rootFirst.stream().filter(l -> !l.isEmpty()).count() > 1;
    for (Layer l : rootFirst) {
      for (String e : l.errors()) {
        errors.add(attribute ? "in " + l.source() + ": " + e : e);
      }
    }
    if (!errors.isEmpty()) {
      return BranchReviewConfig.invalid(ImmutableList.copyOf(errors));
    }

    boolean enabled = false;
    String target = null;
    ImmutableList<RefPattern> sources = ImmutableList.of();
    ImmutableList<RefPattern> excludes = ImmutableList.of();
    boolean delete = false;
    boolean syncEdits = true;
    boolean wip = true;
    Map<String, LayerRoute> routes = new LinkedHashMap<>();
    for (Layer l : rootFirst) {
      enabled = l.enabled().orElse(enabled);
      if (l.targetBranch().isPresent()) {
        target = l.targetBranch().get().isEmpty() ? null : l.targetBranch().get();
      }
      sources = l.sourcePatterns().orElse(sources);
      excludes = l.excludePatterns().orElse(excludes);
      delete = l.deleteSourceBranchOnSubmit().orElse(delete);
      syncEdits = l.syncEditsToBranch().orElse(syncEdits);
      wip = l.workInProgress().orElse(wip);
      l.targets()
          .forEach((t, r) -> routes.put(t, r.over(routes.getOrDefault(t, LayerRoute.EMPTY))));
    }
    if (!enabled) {
      return BranchReviewConfig.disabled();
    }

    List<BranchReviewConfig.Route> effectiveRoutes = new ArrayList<>();
    for (Map.Entry<String, LayerRoute> e : new TreeMap<>(routes).entrySet()) {
      LayerRoute r = e.getValue();
      if (!r.enabled().orElse(true)) {
        continue; // Route removed by a lower file.
      }
      ImmutableList<RefPattern> patterns = r.sourcePatterns().orElse(ImmutableList.of());
      if (patterns.isEmpty()) {
        errors.add(
            "[" + TARGET_SECTION + " \"" + e.getKey() + "\"] needs at least one " + KEY_SOURCE);
      }
      effectiveRoutes.add(
          new BranchReviewConfig.Route(
              e.getKey(),
              patterns,
              r.deleteSourceBranchOnSubmit(),
              r.syncEditsToBranch(),
              r.workInProgress()));
    }

    if (target != null) {
      if (sources.isEmpty()) {
        errors.add(
            "at least one " + SECTION + "." + KEY_SOURCE + " is required with " + KEY_TARGET);
      }
    } else if (!sources.isEmpty()) {
      errors.add(
          SECTION
              + "."
              + KEY_TARGET
              + " is required for the "
              + SECTION
              + "."
              + KEY_SOURCE
              + " patterns");
    } else if (effectiveRoutes.isEmpty()) {
      errors.add(
          "nothing to review: set "
              + SECTION
              + "."
              + KEY_TARGET
              + " with "
              + KEY_SOURCE
              + " patterns, or add [target \"refs/heads/...\"] sections");
    }

    // A pattern naming a target exactly is a mistake: targets are never sources.
    ImmutableSet.Builder<String> targetsBuilder = ImmutableSet.builder();
    if (target != null) {
      targetsBuilder.add(target);
    }
    effectiveRoutes.forEach(r -> targetsBuilder.add(r.target()));
    ImmutableSet<String> targets = targetsBuilder.build();
    List<RefPattern> all = new ArrayList<>(sources);
    effectiveRoutes.forEach(r -> all.addAll(r.sourcePatterns()));
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
    for (BranchReviewConfig.Route r : effectiveRoutes) {
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
        ImmutableList.copyOf(effectiveRoutes),
        delete,
        syncEdits,
        wip,
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

  private static boolean isSet(Config cfg, String section, String sub, String key) {
    return cfg.getNames(section, sub).stream().anyMatch(n -> n.equalsIgnoreCase(key));
  }

  private static Optional<Boolean> parseBoolean(
      Config cfg, String section, String sub, String key, List<String> errors) {
    if (!isSet(cfg, section, sub, key)) {
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

  /**
   * The patterns of {@code key}: empty Optional when the key is absent (inherited); an empty list
   * when it is set to an empty value, which clears the inherited list.
   */
  private static Optional<ImmutableList<RefPattern>> parsePatterns(
      Config cfg, String section, String sub, String key, List<String> errors) {
    if (!isSet(cfg, section, sub, key)) {
      return Optional.empty();
    }
    String[] raws = cfg.getStringList(section, sub, key);
    if (Arrays.stream(raws).allMatch(r -> r == null || r.isEmpty())) {
      return Optional.of(ImmutableList.of());
    }
    ImmutableList.Builder<RefPattern> out = ImmutableList.builder();
    for (String raw : raws) {
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
    return Optional.of(out.build().stream().distinct().collect(ImmutableList.toImmutableList()));
  }
}
