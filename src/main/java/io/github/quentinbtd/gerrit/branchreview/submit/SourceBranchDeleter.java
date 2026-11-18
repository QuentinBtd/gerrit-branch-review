// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.submit;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.validators.RefOperationValidationException;
import com.google.gerrit.server.git.validators.RefOperationValidators;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * Deletes a source branch on behalf of a user, only if it still points at an expected commit: the
 * project state must permit writes (not read-only), the user needs Gerrit's {@code delete}
 * permission, Gerrit's ref operation validators must accept the deletion, and the ref update is a
 * compare-and-swap on that commit, so commits pushed meanwhile are never lost. Used by the cleanup
 * after Submit and by the change-page action.
 */
@Singleton
public class SourceBranchDeleter {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  public enum Outcome {
    DELETED,
    NO_PERMISSION,
    /** Refused by a ref operation validator; the detail holds its message. */
    REJECTED,
    /** The branch no longer points at the expected commit. */
    CHANGED,
    FAILED
  }

  public record Result(Outcome outcome, String detail) {}

  private final PermissionBackend permissionBackend;
  private final RefOperationValidators.Factory refValidatorsFactory;
  private final ProjectCache projectCache;
  private final GitReferenceUpdated gitRefUpdated;

  @Inject
  SourceBranchDeleter(
      PermissionBackend permissionBackend,
      RefOperationValidators.Factory refValidatorsFactory,
      ProjectCache projectCache,
      GitReferenceUpdated gitRefUpdated) {
    this.permissionBackend = permissionBackend;
    this.refValidatorsFactory = refValidatorsFactory;
    this.projectCache = projectCache;
    this.gitRefUpdated = gitRefUpdated;
  }

  /** Whether the project's state permits writes, as Gerrit's own branch deletion requires. */
  public boolean writable(Project.NameKey project) {
    return projectCache.get(project).map(ProjectState::statePermitsWrite).orElse(false);
  }

  /** Whether {@code user} holds Gerrit's {@code delete} permission on {@code ref}. */
  public boolean mayDelete(IdentifiedUser user, Project.NameKey project, String ref) {
    try {
      return permissionBackend.user(user).project(project).ref(ref).test(RefPermission.DELETE);
    } catch (PermissionBackendException e) {
      log.atWarning().withCause(e).log("cannot check delete permission on %s", ref);
      return false;
    }
  }

  public Result delete(
      Repository repo,
      Project.NameKey project,
      String ref,
      ObjectId expected,
      IdentifiedUser user,
      String reflogMessage)
      throws IOException {
    if (!writable(project)) {
      return new Result(Outcome.REJECTED, "project state does not permit write");
    }
    if (!mayDelete(user, project, ref)) {
      return new Result(Outcome.NO_PERMISSION, null);
    }
    String rejection = rejection(project, ref, expected, user);
    if (rejection != null) {
      return new Result(Outcome.REJECTED, rejection);
    }
    RefUpdate ru = repo.updateRef(ref);
    ru.setExpectedOldObjectId(expected);
    ru.setNewObjectId(ObjectId.zeroId());
    ru.setForceUpdate(true);
    ru.setRefLogMessage(reflogMessage, false);
    RefUpdate.Result r;
    try (RefUpdateContext ctx = RefUpdateContext.open(RefUpdateType.BRANCH_MODIFICATION)) {
      r = ru.delete();
    }
    return switch (r) {
      case FORCED, FAST_FORWARD, NO_CHANGE -> {
        gitRefUpdated.fire(project, ru, user.state());
        yield new Result(Outcome.DELETED, null);
      }
      case LOCK_FAILURE, REJECTED -> new Result(Outcome.CHANGED, null);
      default -> new Result(Outcome.FAILED, "delete returned " + r);
    };
  }

  /** The rejection message of Gerrit's ref operation validators, or {@code null}. */
  private String rejection(Project.NameKey project, String ref, ObjectId old, IdentifiedUser user) {
    try {
      ProjectState state = projectCache.get(project).orElse(null);
      if (state == null) {
        return "project not found";
      }
      refValidatorsFactory
          .create(
              state.getProject(),
              user,
              new ReceiveCommand(old, ObjectId.zeroId(), ref, ReceiveCommand.Type.DELETE),
              ImmutableListMultimap.of())
          .validateForRefOperation();
      return null;
    } catch (RefOperationValidationException e) {
      StringBuilder msg = new StringBuilder(String.valueOf(e.getMessage()));
      e.getMessages().forEach(v -> msg.append("; ").append(v.getMessage()));
      return msg.toString();
    }
  }
}
