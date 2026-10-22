// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.api;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import io.github.quentinbtd.gerrit.branchreview.config.ProjectConfigLoader;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncResult;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

/**
 * Permission-filtered status lookup and explicit resynchronization, shared by REST and SSH.
 *
 * <p>Callers must be able to read the source ref; otherwise the branch is reported as not found, so
 * that hidden branch names are not confirmed. Change details are included only if the caller can
 * see the Change.
 */
@Singleton
public class BranchStatus {
  private final GitRepositoryManager repoManager;
  private final StateStore store;
  private final ProjectConfigLoader configLoader;
  private final PermissionBackend permissionBackend;
  private final ChangeNotes.Factory notesFactory;
  private final DynamicItem<UrlFormatter> urlFormatter;
  private final SyncService syncService;

  @Inject
  BranchStatus(
      GitRepositoryManager repoManager,
      StateStore store,
      ProjectConfigLoader configLoader,
      PermissionBackend permissionBackend,
      ChangeNotes.Factory notesFactory,
      DynamicItem<UrlFormatter> urlFormatter,
      SyncService syncService) {
    this.repoManager = repoManager;
    this.store = store;
    this.configLoader = configLoader;
    this.permissionBackend = permissionBackend;
    this.notesFactory = notesFactory;
    this.urlFormatter = urlFormatter;
    this.syncService = syncService;
  }

  public static String normalizeRef(String ref) throws BadRequestException {
    if (ref == null || ref.isBlank()) {
      throw new BadRequestException("ref is required");
    }
    String r = ref.strip();
    if (!r.startsWith("refs/")) {
      r = "refs/heads/" + r;
    }
    if (!BranchReviewConfig.isOrdinaryBranch(r) || !Repository.isValidRefName(r)) {
      throw new BadRequestException("not a branch: " + ref);
    }
    return r;
  }

  public StatusInfo status(Project.NameKey project, String ref, CurrentUser user)
      throws RestApiException, IOException, PermissionBackendException {
    String sourceRef = normalizeRef(ref);
    if (!permissionBackend.user(user).project(project).ref(sourceRef).test(RefPermission.READ)) {
      throw new ResourceNotFoundException(ref);
    }
    BranchReviewConfig cfg = configLoader.load(project);
    StatusInfo info = new StatusInfo();
    info.project = project.get();
    info.sourceRef = sourceRef;
    info.configStatus = cfg.status().name();
    info.configErrors = cfg.errors().isEmpty() ? null : cfg.errors();
    info.eligible = cfg.isEligibleSource(sourceRef);
    List<String> problems = new ArrayList<>();
    try (Repository repo = repoManager.openRepository(project)) {
      Ref src = repo.exactRef(sourceRef);
      info.currentSourceId = src == null ? null : src.getObjectId().name();
      Lifecycle lc = store.loadLifecycle(repo, sourceRef).value();
      if (lc != null) {
        info.lifecycleId = lc.id;
        info.lifecycleStatus = lc.status.name();
        info.syncState = lc.syncState.name();
        info.targetRef = lc.targetRef;
        info.lastSourceId = lc.lastSourceId;
        info.lastError = lc.lastError;
        info.attempts = lc.attempts;
        info.pending = lc.pending != null;
        if (lc.merge != null) {
          info.cleanup = lc.merge.cleanup;
          info.cleanupDetail = lc.merge.cleanupDetail;
        }
        info.inSync =
            lc.syncState == Lifecycle.SyncState.SYNCED
                && info.currentSourceId != null
                && info.currentSourceId.equals(lc.lastSourceId);
        if (lc.changeNumber > 0) {
          try {
            ChangeNotes notes = notesFactory.createChecked(project, Change.id(lc.changeNumber));
            if (permissionBackend.user(user).change(notes).test(ChangePermission.READ)) {
              info.changeNumber = lc.changeNumber;
              info.changeStatus = notes.getChange().getStatus().name();
              info.workInProgress = notes.getChange().isWorkInProgress();
              info.patchSet = notes.getChange().currentPatchSetId().get();
              info.changeUrl =
                  urlFormatter
                      .get()
                      .getChangeViewUrl(project, Change.id(lc.changeNumber))
                      .orElse(null);
              if (lc.isActive() && notes.getChange().isMerged()) {
                problems.add("Change is merged but the lifecycle is still active");
              }
            }
          } catch (NoSuchChangeException e) {
            problems.add("mapped Change does not exist");
          }
        }
        if (lc.isActive() && cfg.isEnabled() && !cfg.targetBranch().equals(lc.targetRef)) {
          problems.add("configured targetBranch differs from the lifecycle target " + lc.targetRef);
        }
        if (lc.isActive()
            && lc.syncState == Lifecycle.SyncState.SYNCED
            && info.currentSourceId != null
            && !info.currentSourceId.equals(lc.lastSourceId)) {
          problems.add("source advanced without synchronization; run branch-review sync");
        }
      }
    }
    info.problems = problems.isEmpty() ? null : problems;
    return info;
  }

  /**
   * Explicit resynchronization as the calling user. Requires push permission on the source ref;
   * Change creation and patch set permissions are then checked by the normal sync path.
   */
  public SyncResult sync(
      Project.NameKey project, String ref, CurrentUser user, SyncService.Options opts)
      throws RestApiException, PermissionBackendException {
    String sourceRef = normalizeRef(ref);
    if (!user.isIdentifiedUser()) {
      throw new AuthException("authentication required");
    }
    PermissionBackend.ForRef perm = permissionBackend.user(user).project(project).ref(sourceRef);
    if (!perm.test(RefPermission.READ)) {
      throw new ResourceNotFoundException(ref);
    }
    perm.check(RefPermission.UPDATE);
    return syncService.sync(
        project, sourceRef, user.asIdentifiedUser(), SyncService.Trigger.MANUAL, opts);
  }
}
