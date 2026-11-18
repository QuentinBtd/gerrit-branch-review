// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.api;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.ChangeContext;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.config.ServerSettings;
import io.github.quentinbtd.gerrit.branchreview.state.ChangeLink;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore.ConcurrentUpdateException;
import io.github.quentinbtd.gerrit.branchreview.submit.SourceBranchDeleter;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncService;
import java.io.IOException;
import java.util.concurrent.locks.Lock;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

/**
 * {@code POST /changes/{id}/branch-review~delete-source-branch}: deletes the source branch of a
 * closed (merged or abandoned) branch-review Change. Gerrit shows it as a change action to users
 * who may delete the branch. Refused unless the branch still points at the source commit the Change
 * represents, so commits pushed after the last synchronization are never lost.
 */
@Singleton
public class DeleteSourceBranch
    implements RestModifyView<ChangeResource, DeleteSourceBranch.Input>, UiAction<ChangeResource> {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  public static class Input {}

  public static class Output {
    public String ref;
    public String commit;
  }

  private record Target(Lifecycle lc, ObjectId stateId, String ref, ObjectId tip) {}

  private final GitRepositoryManager repoManager;
  private final StateStore store;
  private final SyncService syncService;
  private final SourceBranchDeleter deleter;
  private final PermissionBackend permissionBackend;
  private final ServerSettings settings;
  private final BatchUpdate.Factory batchUpdateFactory;
  private final ChangeMessagesUtil messages;

  @Inject
  DeleteSourceBranch(
      GitRepositoryManager repoManager,
      StateStore store,
      SyncService syncService,
      SourceBranchDeleter deleter,
      PermissionBackend permissionBackend,
      ServerSettings settings,
      BatchUpdate.Factory batchUpdateFactory,
      ChangeMessagesUtil messages) {
    this.repoManager = repoManager;
    this.store = store;
    this.syncService = syncService;
    this.deleter = deleter;
    this.permissionBackend = permissionBackend;
    this.settings = settings;
    this.batchUpdateFactory = batchUpdateFactory;
    this.messages = messages;
  }

  @Override
  public Description getDescription(ChangeResource rsrc) {
    Description d = new Description().setLabel("Delete source branch").setVisible(false);
    if (!rsrc.getUser().isIdentifiedUser()) {
      return d;
    }
    try (Repository repo = repoManager.openRepository(rsrc.getProject())) {
      Target t = resolve(repo, rsrc);
      return d.setTitle("Delete " + t.ref() + " now that this Change is closed")
          .setVisible(
              deleter.mayDelete(rsrc.getUser().asIdentifiedUser(), rsrc.getProject(), t.ref()));
    } catch (RestApiException | IOException | RuntimeException e) {
      return d; // Not applicable (or unreadable): no button.
    } catch (Exception e) {
      log.atWarning().withCause(e).log("cannot describe delete-source-branch");
      return d;
    }
  }

  @Override
  public Response<Output> apply(ChangeResource rsrc, Input in) throws Exception {
    if (!rsrc.getUser().isIdentifiedUser()) {
      throw new AuthException("Authentication required");
    }
    IdentifiedUser user = rsrc.getUser().asIdentifiedUser();
    Project.NameKey project = rsrc.getProject();
    int number = rsrc.getId().get();
    Target t;
    try (Repository repo = repoManager.openRepository(project)) {
      Lock lock = syncService.lockFor(project, resolve(repo, rsrc).ref());
      lock.lock();
      try {
        // Again under the branch lock: a push may have been synchronized meanwhile.
        t = resolve(repo, rsrc);
        SourceBranchDeleter.Result r =
            deleter.delete(
                repo,
                project,
                t.ref(),
                t.tip(),
                user,
                "branch-review: delete source of change " + number + " from the change page");
        switch (r.outcome()) {
          case DELETED -> record(repo, project, t, user, number);
          case NO_PERMISSION -> throw new AuthException("delete not permitted on " + t.ref());
          case REJECTED -> throw new ResourceConflictException("deletion rejected: " + r.detail());
          case CHANGED ->
              throw new ResourceConflictException(t.ref() + " changed meanwhile; it was kept");
          default -> throw new IOException("cannot delete " + t.ref() + ": " + r.detail());
        }
      } finally {
        lock.unlock();
      }
    }
    postMessage(rsrc, user, t);
    Output o = new Output();
    o.ref = t.ref();
    o.commit = t.tip().name();
    return Response.ok(o);
  }

  /** The branch to delete and the commit it must point at, or why the action does not apply. */
  private Target resolve(Repository repo, ChangeResource rsrc) throws Exception {
    if (settings.emergencyDisabled()) {
      throw new ResourceConflictException("branch-review is disabled");
    }
    if (!rsrc.getChange().getStatus().isClosed()) {
      throw new ResourceConflictException("the Change is still open");
    }
    StateStore.Loaded<ChangeLink> link = store.loadLink(repo, rsrc.getId().get());
    if (!link.exists()) {
      throw new ResourceNotFoundException("not a branch-review Change");
    }
    String ref = link.value().sourceRef;
    if (!permissionBackend
        .user(rsrc.getUser())
        .project(rsrc.getProject())
        .ref(ref)
        .test(RefPermission.READ)) {
      throw new ResourceNotFoundException("not a branch-review Change");
    }
    StateStore.Loaded<Lifecycle> loaded = store.loadLifecycle(repo, ref);
    Lifecycle lc = loaded.value();
    if (lc == null || !lc.id.equals(link.value().lifecycleId)) {
      throw new ResourceConflictException(ref + " now belongs to another review");
    }
    Ref src = repo.exactRef(ref);
    if (src == null || src.getObjectId() == null) {
      throw new ResourceConflictException(ref + " no longer exists");
    }
    String expected =
        lc.merge != null && lc.merge.submittedSourceId != null
            ? lc.merge.submittedSourceId
            : lc.lastSourceId;
    if (!src.getObjectId().name().equals(expected)) {
      throw new ResourceConflictException(
          ref + " has commits that are not in this Change; it was kept");
    }
    return new Target(lc, loaded.refId(), ref, src.getObjectId());
  }

  /** Records the deletion as the lifecycle would see it after a push or a Submit cleanup. */
  private void record(
      Repository repo, Project.NameKey project, Target t, IdentifiedUser user, int number)
      throws IOException {
    Lifecycle lc = t.lc();
    if (lc.isActive()) {
      lc.syncState = Lifecycle.SyncState.SOURCE_DELETED;
      lc.observedSourceId = null;
      lc.lastActor = user.getAccountId().get();
    } else if (lc.merge != null) {
      lc.merge.cleanup = "deleted";
      lc.merge.cleanupDetail = "deleted " + t.ref() + " at " + t.tip().name() + " from the change";
    } else {
      return;
    }
    try {
      store.saveLifecycle(repo, project, lc, t.stateId(), "source deleted from change " + number);
    } catch (ConcurrentUpdateException e) {
      // The branch is gone either way; a later push or recovery records it.
      log.atWarning().withCause(e).log("cannot record deletion of %s", t.ref());
    }
  }

  private void postMessage(ChangeResource rsrc, IdentifiedUser user, Target t) {
    String branch = t.ref().replaceFirst("^refs/heads/", "");
    String message =
        "Deleted source branch " + branch + " (was at " + t.tip().abbreviate(7).name() + ").";
    var ps = rsrc.getChange().currentPatchSetId();
    try (RefUpdateContext ctx = RefUpdateContext.open(RefUpdateType.CHANGE_MODIFICATION);
        BatchUpdate bu = batchUpdateFactory.create(rsrc.getProject(), user, TimeUtil.now())) {
      bu.addOp(
          rsrc.getId(),
          new BatchUpdateOp() {
            @Override
            public boolean updateChange(ChangeContext ctx) {
              messages.setChangeMessage(ctx.getUpdate(ps), message, null);
              return true;
            }
          });
      bu.execute();
    } catch (Exception e) {
      // The deletion happened; only its note on the Change is missing.
      log.atWarning().withCause(e).log("cannot post deletion message on %s", rsrc.getId());
    }
  }
}
