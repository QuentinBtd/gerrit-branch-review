// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.compat;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.validators.CommitValidators;
import com.google.gerrit.server.permissions.PermissionBackend;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Version adapter (Gerrit 3.14): {@code forReceiveCommits} has no {@code SshInfo} parameter. Only
 * the validator selection differs between series; see the sibling source set for 3.12 and 3.13.
 */
public final class Validators {
  private Validators() {}

  /** The validators Gerrit runs for a direct push of {@code branch} (banned commits excluded). */
  public static CommitValidators forBranchPush(
      CommitValidators.Factory factory,
      PermissionBackend.ForProject perm,
      BranchNameKey branch,
      IdentifiedUser user,
      RevWalk rw) {
    return factory.forReceiveCommits(perm, branch, user, NoteMap.newEmptyMap(), rw, null, false);
  }
}
