// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.sync;

import com.google.common.collect.ImmutableList;

/** Outcome of synchronizing one source ref, rendered as push output and API responses. */
public record SyncResult(
    Kind kind,
    String sourceRef,
    int changeNumber,
    int patchSet,
    boolean workInProgress,
    String url,
    ImmutableList<String> details) {

  public enum Kind {
    CREATED("created"),
    UPDATED("updated"),
    NOOP("noop"),
    /** Not managed (disabled, not eligible). Nothing is reported to the client. */
    IGNORED("ignored"),
    /** Accepted source state that is deliberately not synchronized (empty, conflict, ...). */
    NOT_SYNCHRONIZED("not_synchronized"),
    /** Source deleted; lifecycle kept. */
    SOURCE_DELETED("source_deleted"),
    /** Synchronization failed after the source update was accepted; a retry is recorded. */
    FAILED("failed"),
    /** Still running when the push stopped waiting. */
    IN_PROGRESS("in_progress");

    private final String metric;

    Kind(String metric) {
      this.metric = metric;
    }

    public String metric() {
      return metric;
    }
  }

  public static SyncResult ignored(String ref) {
    return new SyncResult(Kind.IGNORED, ref, 0, 0, false, null, ImmutableList.of());
  }

  public static SyncResult of(Kind kind, String ref, String... details) {
    return new SyncResult(kind, ref, 0, 0, false, null, ImmutableList.copyOf(details));
  }

  public SyncResult withChange(int change, int ps, boolean wip, String url) {
    return new SyncResult(kind, sourceRef, change, ps, wip, url, details);
  }

  /** Lines for the Git client, each prefixed with "branch-review: " by the caller. */
  public ImmutableList<String> render() {
    ImmutableList.Builder<String> out = ImmutableList.builder();
    String state = workInProgress ? "WIP" : "ready";
    switch (kind) {
      case CREATED -> out.add(sourceRef + ": created Change " + changeNumber + " (" + state + ")");
      case UPDATED ->
          out.add(
              sourceRef
                  + ": updated Change "
                  + changeNumber
                  + " with patch set "
                  + patchSet
                  + " ("
                  + state
                  + ")");
      case NOOP ->
          out.add(
              sourceRef
                  + ": Change "
                  + changeNumber
                  + " is up to date (patch set "
                  + patchSet
                  + ", "
                  + state
                  + "), no new patch set");
      case NOT_SYNCHRONIZED ->
          out.add(sourceRef + ": branch update accepted but NOT synchronized to review");
      case SOURCE_DELETED -> out.add(sourceRef + ": source branch deleted");
      case FAILED ->
          out.add(
              sourceRef
                  + ": branch update accepted, review synchronization FAILED (retry recorded)");
      case IN_PROGRESS ->
          out.add(sourceRef + ": branch update accepted, review synchronization still running");
      case IGNORED -> {}
    }
    details.forEach(out::add);
    if (url != null) {
      out.add(url);
    }
    return out.build();
  }
}
