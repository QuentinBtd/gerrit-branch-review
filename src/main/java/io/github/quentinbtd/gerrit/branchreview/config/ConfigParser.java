// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;

/** Strict parser for {@code branch-review.config}. Pure; performs no I/O. */
public final class ConfigParser {
  public static final String FILE_NAME = "branch-review.config";
  public static final String SECTION = "branchReview";

  static final String KEY_ENABLED = "enabled";
  static final String KEY_TARGET = "targetBranch";
  static final String KEY_SOURCE = "sourceBranchPattern";
  static final String KEY_EXCLUDE = "excludeBranchPattern";
  static final String KEY_DELETE = "deleteSourceBranchOnSubmit";
  static final String KEY_SYNC_EDITS = "syncEditsToBranch";

  private static final ImmutableSet<String> KNOWN_KEYS =
      ImmutableSet.of(KEY_ENABLED, KEY_TARGET, KEY_SOURCE, KEY_EXCLUDE, KEY_DELETE, KEY_SYNC_EDITS);

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
      if (!section.equals(SECTION)) {
        errors.add("unknown section [" + section + "]");
      }
    }
    if (!cfg.getSubsections(SECTION).isEmpty()) {
      errors.add("subsections of [" + SECTION + "] are not supported");
    }
    for (String name : cfg.getNames(SECTION)) {
      if (KNOWN_KEYS.stream().noneMatch(k -> k.equalsIgnoreCase(name))) {
        errors.add("unknown key " + SECTION + "." + name);
      }
    }
    for (String single : List.of(KEY_ENABLED, KEY_TARGET, KEY_DELETE, KEY_SYNC_EDITS)) {
      if (cfg.getStringList(SECTION, null, single).length > 1) {
        errors.add(SECTION + "." + single + " must be set at most once");
      }
    }

    Boolean enabled = parseBoolean(cfg, KEY_ENABLED, false, errors);
    Boolean delete = parseBoolean(cfg, KEY_DELETE, false, errors);
    Boolean syncEdits = parseBoolean(cfg, KEY_SYNC_EDITS, true, errors);
    // Patterns are validated even when disabled so that a file can be reviewed before enabling.
    ImmutableList<RefPattern> sources = parsePatterns(cfg, KEY_SOURCE, errors);
    ImmutableList<RefPattern> excludes = parsePatterns(cfg, KEY_EXCLUDE, errors);
    if (!errors.isEmpty()) {
      return BranchReviewConfig.invalid(ImmutableList.copyOf(errors));
    }
    if (!enabled) {
      return BranchReviewConfig.disabled();
    }

    String target = cfg.getString(SECTION, null, KEY_TARGET);
    if (target == null || target.isEmpty()) {
      errors.add(SECTION + "." + KEY_TARGET + " is required when enabled");
    } else if (!target.startsWith("refs/heads/")
        || target.contains("*")
        || !Repository.isValidRefName(target)
        || target.startsWith(BranchReviewConfig.STATE_NAMESPACE)) {
      errors.add(
          SECTION
              + "."
              + KEY_TARGET
              + " must be a fully qualified branch such as refs/heads/master: "
              + target);
    }

    if (sources.isEmpty()) {
      errors.add("at least one " + SECTION + "." + KEY_SOURCE + " is required when enabled");
    }
    if (target != null) {
      for (RefPattern p : sources) {
        if (!p.isWildcard() && p.toString().equals(target)) {
          errors.add(KEY_SOURCE + " " + p + " names the target branch");
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
        delete,
        syncEdits,
        ImmutableList.of());
  }

  private static Boolean parseBoolean(Config cfg, String key, boolean def, List<String> errors) {
    try {
      return cfg.getBoolean(SECTION, null, key, def);
    } catch (IllegalArgumentException e) {
      errors.add(SECTION + "." + key + " must be true or false");
      return def;
    }
  }

  private static ImmutableList<RefPattern> parsePatterns(
      Config cfg, String key, List<String> errors) {
    ImmutableList.Builder<RefPattern> out = ImmutableList.builder();
    for (String raw : cfg.getStringList(SECTION, null, key)) {
      try {
        out.add(RefPattern.parse(raw));
      } catch (IllegalArgumentException e) {
        errors.add(SECTION + "." + key + " '" + raw + "': " + e.getMessage());
      }
    }
    return out.build().stream().distinct().collect(ImmutableList.toImmutableList());
  }
}
