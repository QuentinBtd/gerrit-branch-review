// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.config;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Server-wide settings from {@code [plugin "branch-review"]} in {@code gerrit.config}.
 *
 * <p>These can only restrict or bound the plugin; they never enable a project. A project is enabled
 * exclusively by its own {@code branch-review.config}.
 */
@Singleton
public class ServerSettings {
  private final boolean emergencyDisabled;
  private final int maxCommits;
  private final int pushWaitSeconds;
  private final int threads;
  private final int maxQueued;
  private final int recoveryIntervalSeconds;
  private final int maxAttempts;

  @Inject
  ServerSettings(PluginConfigFactory cfgFactory, @PluginName String pluginName) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName);
    emergencyDisabled = cfg.getBoolean("disabled", false);
    maxCommits = clamp(cfg.getInt("maxCommits", 2000), 1, 100_000);
    pushWaitSeconds = clamp(cfg.getInt("pushWaitSeconds", 60), 1, 600);
    threads = clamp(cfg.getInt("threads", 2), 1, 32);
    maxQueued = clamp(cfg.getInt("maxQueued", 200), 1, 100_000);
    recoveryIntervalSeconds = clamp(cfg.getInt("recoveryIntervalSeconds", 300), 0, 86_400);
    maxAttempts = clamp(cfg.getInt("maxAttempts", 5), 1, 100);
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }

  /** Emergency switch: when set, no synchronization, guard or cleanup mutation happens. */
  public boolean emergencyDisabled() {
    return emergencyDisabled;
  }

  public int maxCommits() {
    return maxCommits;
  }

  public int pushWaitSeconds() {
    return pushWaitSeconds;
  }

  public int threads() {
    return threads;
  }

  public int maxQueued() {
    return maxQueued;
  }

  /** 0 disables periodic recovery; startup recovery still runs. */
  public int recoveryIntervalSeconds() {
    return recoveryIntervalSeconds;
  }

  public int maxAttempts() {
    return maxAttempts;
  }
}
