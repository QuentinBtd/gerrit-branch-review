// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.api;

import java.util.List;

/** JSON returned by {@code GET /projects/{project}/branch-review~status?ref=...}. */
public class StatusInfo {
  public String project;
  public String sourceRef;
  public String configStatus;
  public List<String> configErrors;
  public boolean eligible;
  public String currentSourceId;
  public String lifecycleId;
  public String lifecycleStatus;
  public String syncState;
  public String targetRef;
  public Integer changeNumber;
  public String changeUrl;
  public String changeStatus;
  public Boolean workInProgress;
  public Integer patchSet;
  public String lastSourceId;
  public Boolean inSync;
  public String lastError;
  public Integer attempts;
  public Boolean pending;
  public String cleanup;
  public String cleanupDetail;
  public List<String> problems;
}
