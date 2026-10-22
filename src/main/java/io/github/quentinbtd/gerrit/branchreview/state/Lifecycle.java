// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.state;

import io.github.quentinbtd.gerrit.branchreview.text.TextOwnership;

/**
 * Durable state of one review lifecycle for a (project, source ref) pair.
 *
 * <p>Serialized as JSON into the plugin-owned state ref of the source branch; see {@link
 * StateStore}. Field names are part of the persisted format: rename only with a migration.
 */
public final class Lifecycle {
  public static final int FORMAT = 1;

  /** Lifecycle status. Only {@link #ACTIVE} lifecycles receive patch sets. */
  public enum Status {
    ACTIVE,
    MERGED,
    /** Closed by an explicit {@code --new-lifecycle} repair after abandon or source deletion. */
    CLOSED
  }

  /** Synchronization state of an active lifecycle. */
  public enum SyncState {
    /** The current patch set represents {@link #lastSourceId}. */
    SYNCED,
    /** A Change/patch set insertion was reserved but not confirmed; see {@link Pending}. */
    PENDING,
    /** The last accepted source update could not be synchronized; it will be retried. */
    FAILED,
    /** The latest source state has no net change; the current patch set is stale. */
    EMPTY,
    /** The latest source state conflicts with the target. */
    CONFLICT,
    /** The latest source state shares no history with the target or exceeds limits. */
    UNSUPPORTED,
    /** A patch set not produced by the plugin changed the reviewed content. */
    DIVERGED,
    /** Configuration no longer matches the lifecycle (e.g. target changed). */
    BLOCKED,
    /** The source branch was deleted. */
    SOURCE_DELETED
  }

  /** A reserved insertion whose outcome must be verified against NoteDb before anything else. */
  public static final class Pending {
    public String op; // "create" or "patchset"
    public int changeNumber;
    public int patchSet;
    public String commit;
    public String sourceId;
    public String targetId;
    public String startedOn;

    /** Text ownership state to record once the insertion is confirmed. */
    public Text text;
  }

  public static final class Text {
    public String lastGeneratedSubject;
    public String lastGeneratedBody;
    public TextOwnership.Owner subjectOwner = TextOwnership.Owner.GENERATOR;
    public TextOwnership.Owner bodyOwner = TextOwnership.Owner.GENERATOR;

    public TextOwnership.State toState() {
      return new TextOwnership.State(
          lastGeneratedSubject, lastGeneratedBody, subjectOwner, bodyOwner);
    }

    public void apply(TextOwnership.State s) {
      lastGeneratedSubject = s.lastGeneratedSubject();
      lastGeneratedBody = s.lastGeneratedBody();
      subjectOwner = s.subject();
      bodyOwner = s.body();
    }
  }

  public static final class Merge {
    public String mergedCommit;
    public String submittedSourceId;
    public int submitter;
    public String cleanup; // none, pending, deleted, skipped, failed
    public String cleanupDetail;
    public int cleanupAttempts;
  }

  public int format = FORMAT;
  public String id;
  public String project;
  public String sourceRef;
  public String targetRef;
  public int changeNumber;
  public String changeKey;
  public int owner;
  public Status status = Status.ACTIVE;
  public SyncState syncState = SyncState.PENDING;
  public String lastSourceId;
  public String lastTargetId;
  public String lastSyntheticCommit;
  public int lastSyntheticPatchSet;

  /** Last source OID observed (may be newer than {@link #lastSourceId} when not synced). */
  public String observedSourceId;

  public int lastActor;
  public String lastError;
  public int attempts;
  public String nextAttemptAfter;
  public String createdOn;
  public String updatedOn;

  /** Previous lifecycle of the same source ref, if any. */
  public String previousLifecycleId;

  public int previousChangeNumber;
  public String previousMergedCommit;
  public Pending pending;
  public Text text = new Text();
  public Merge merge;

  public boolean isActive() {
    return status == Status.ACTIVE;
  }

  public boolean hasChange() {
    return changeNumber > 0 && lastSyntheticPatchSet > 0;
  }
}
