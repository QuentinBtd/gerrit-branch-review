// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.state;

/**
 * Reverse mapping from a Change number to the lifecycle that owns it. Written before the Change is
 * inserted, so that a Change created by the plugin can always be traced back, even after a crash.
 */
public final class ChangeLink {
  public int format = Lifecycle.FORMAT;
  public String lifecycleId;
  public String sourceRef;
  public String changeKey;
}
