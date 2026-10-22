// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.submit;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.ChangeMergedListener;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.approval.ApprovalsUtil;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.RefOperationValidationException;
import com.google.gerrit.server.git.validators.RefOperationValidators;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.Metrics;
import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import io.github.quentinbtd.gerrit.branchreview.config.ProjectConfigLoader;
import io.github.quentinbtd.gerrit.branchreview.config.ServerSettings;
import io.github.quentinbtd.gerrit.branchreview.state.ChangeLink;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore.ConcurrentUpdateException;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncExecutor;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncService;
import java.io.IOException;
import java.util.concurrent.locks.Lock;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * Closes a lifecycle when its Change is merged and performs the optional source branch cleanup.
 *
 * <p>Cleanup runs only after Gerrit reports the merge, never on approval. The source ref is deleted
 * only if, at cleanup time, the project still enables {@code deleteSourceBranchOnSubmit}, the ref
 * is still an eligible source of this lifecycle, it still points at exactly the source commit
 * represented by the submitted patch set, and the submitting user holds the Gerrit {@code delete}
 * permission on it. The deletion is a compare-and-swap on that commit. A failed cleanup never
 * affects the Submit and is retried a bounded number of times.
 */
@Singleton
public class MergeHandler implements ChangeMergedListener {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  private final GitRepositoryManager repoManager;
  private final StateStore store;
  private final ProjectConfigLoader configLoader;
  private final ServerSettings settings;
  private final SyncService syncService;
  private final SyncExecutor executor;
  private final PermissionBackend permissionBackend;
  private final ChangeNotes.Factory notesFactory;
  private final GitReferenceUpdated gitRefUpdated;
  private final AccountCache accountCache;
  private final Metrics metrics;
  private final ApprovalsUtil approvalsUtil;
  private final RefOperationValidators.Factory refValidatorsFactory;
  private final IdentifiedUser.GenericFactory userFactory;
  private final ProjectCache projectCache;

  @Inject
  MergeHandler(
      GitRepositoryManager repoManager,
      StateStore store,
      ProjectConfigLoader configLoader,
      ServerSettings settings,
      SyncService syncService,
      SyncExecutor executor,
      PermissionBackend permissionBackend,
      ChangeNotes.Factory notesFactory,
      GitReferenceUpdated gitRefUpdated,
      AccountCache accountCache,
      Metrics metrics,
      ApprovalsUtil approvalsUtil,
      RefOperationValidators.Factory refValidatorsFactory,
      IdentifiedUser.GenericFactory userFactory,
      ProjectCache projectCache) {
    this.refValidatorsFactory = refValidatorsFactory;
    this.userFactory = userFactory;
    this.projectCache = projectCache;
    this.approvalsUtil = approvalsUtil;
    this.repoManager = repoManager;
    this.store = store;
    this.configLoader = configLoader;
    this.settings = settings;
    this.syncService = syncService;
    this.executor = executor;
    this.permissionBackend = permissionBackend;
    this.notesFactory = notesFactory;
    this.gitRefUpdated = gitRefUpdated;
    this.accountCache = accountCache;
    this.metrics = metrics;
  }

  @Override
  public void onChangeMerged(Event event) {
    if (settings.emergencyDisabled()) {
      return;
    }
    Project.NameKey project = Project.nameKey(event.getChange().project);
    int number = event.getChange()._number;
    int submitter =
        event.getWho() == null || event.getWho()._accountId == null ? 0 : event.getWho()._accountId;
    String newRev = event.getNewRevisionId();
    if (!executor.execute(() -> handleMerged(project, number, submitter, newRev))) {
      log.atWarning().log(
          "cannot schedule merge handling of %s change %d; recovery will retry", project, number);
    }
  }

  /** Records the merge and runs cleanup. Idempotent: repeated events change nothing. */
  public void handleMerged(Project.NameKey project, int number, int submitter, String newRev) {
    if (!configLoader.load(project).isEnabled()) {
      // Disabled projects get no plugin work; a later push reconciles the lifecycle.
      return;
    }
    try (Repository repo = repoManager.openRepository(project)) {
      StateStore.Loaded<ChangeLink> link = store.loadLink(repo, number);
      if (!link.exists()) {
        return;
      }
      String sourceRef = link.value().sourceRef;
      Lock lock = syncService.lockFor(project, sourceRef);
      lock.lock();
      try {
        StateStore.Loaded<Lifecycle> loaded = store.loadLifecycle(repo, sourceRef);
        Lifecycle lc = loaded.value();
        if (lc == null || !lc.id.equals(link.value().lifecycleId)) {
          return; // Lifecycle replaced since; nothing to do for the old Change.
        }
        ChangeNotes notes = notesFactory.createChecked(project, Change.id(number));
        if (!notes.getChange().isMerged()) {
          return;
        }
        ObjectId stateId = loaded.refId();
        boolean changed = false;
        if (lc.status != Lifecycle.Status.MERGED) {
          lc.status = Lifecycle.Status.MERGED;
          changed = true;
        }
        if (lc.merge == null) {
          lc.merge = new Lifecycle.Merge();
          lc.merge.cleanup = "none";
          changed = true;
        }
        if (lc.merge.mergedCommit == null) {
          lc.merge.mergedCommit =
              newRev != null ? newRev : notes.getCurrentPatchSet().commitId().name();
          lc.merge.submittedSourceId = lc.lastSourceId;
          changed = true;
        }
        int who = submitter != 0 ? submitter : storedSubmitter(notes);
        if (lc.merge.submitter == 0 && who != 0) {
          lc.merge.submitter = who;
          BranchReviewConfig cfg = configLoader.load(project);
          if (cfg.isEnabled()
              && cfg.deleteSourceBranchOnSubmit()
              && "none".equals(lc.merge.cleanup)) {
            lc.merge.cleanup = "pending";
          }
          changed = true;
        }
        if (changed) {
          stateId = store.saveLifecycle(repo, project, lc, stateId, "change " + number + " merged");
        }
        if ("pending".equals(lc.merge.cleanup)
            || ("failed".equals(lc.merge.cleanup)
                && lc.merge.cleanupAttempts < settings.maxAttempts())) {
          cleanup(repo, project, lc, stateId);
        }
      } finally {
        lock.unlock();
      }
    } catch (IOException | ConcurrentUpdateException | RuntimeException e) {
      log.atWarning().withCause(e).log("merge handling of %s change %d failed", project, number);
    }
  }

  private void cleanup(Repository repo, Project.NameKey project, Lifecycle lc, ObjectId stateId)
      throws IOException, ConcurrentUpdateException {
    Lifecycle.Merge m = lc.merge;
    String outcome;
    String detail;
    String rejection;
    BranchReviewConfig cfg = configLoader.load(project);
    Ref src = repo.exactRef(lc.sourceRef);
    if (!cfg.isEnabled() || !cfg.deleteSourceBranchOnSubmit()) {
      outcome = "skipped";
      detail = "deleteSourceBranchOnSubmit is not enabled at cleanup time";
    } else if (!cfg.isEligibleSource(lc.sourceRef)) {
      outcome = "skipped";
      detail = "branch is no longer an eligible source";
    } else if (src == null) {
      outcome = "skipped";
      detail = "branch already absent";
    } else if (m.submittedSourceId == null
        || !src.getObjectId().name().equals(m.submittedSourceId)) {
      outcome = "skipped";
      detail = "branch advanced past the submitted state; kept";
    } else if (!mayDelete(project, lc.sourceRef, m.submitter)) {
      outcome = "skipped";
      detail = "submitter has no delete permission on the branch";
    } else if ((rejection = deletionRejected(project, lc.sourceRef, src.getObjectId(), m.submitter))
        != null) {
      outcome = "skipped";
      detail = "deletion rejected by validation: " + rejection;
    } else {
      RefUpdate ru = repo.updateRef(lc.sourceRef);
      ru.setExpectedOldObjectId(ObjectId.fromString(m.submittedSourceId));
      ru.setNewObjectId(ObjectId.zeroId());
      ru.setForceUpdate(true);
      ru.setRefLogMessage(
          "branch-review: delete source after submit of change " + lc.changeNumber, false);
      RefUpdate.Result r;
      try (RefUpdateContext ctx = RefUpdateContext.open(RefUpdateType.BRANCH_MODIFICATION)) {
        r = ru.delete();
      }
      switch (r) {
        case FORCED, FAST_FORWARD, NO_CHANGE -> {
          gitRefUpdated.fire(project, ru, accountCache.get(Account.id(m.submitter)).orElse(null));
          outcome = "deleted";
          detail = "deleted " + lc.sourceRef + " at " + m.submittedSourceId;
        }
        case LOCK_FAILURE, REJECTED -> {
          outcome = "skipped";
          detail = "branch changed concurrently; kept";
        }
        default -> {
          outcome = "failed";
          detail = "delete returned " + r;
        }
      }
    }
    if ("failed".equals(outcome)) {
      m.cleanupAttempts++;
    }
    m.cleanup = outcome;
    m.cleanupDetail = detail;
    store.saveLifecycle(repo, project, lc, stateId, "cleanup " + outcome);
    metrics.cleanup(outcome);
    log.atInfo().log(
        "branch-review cleanup project=%s ref=%s change=%d outcome=%s detail=%s",
        project, lc.sourceRef, lc.changeNumber, outcome, detail);
  }

  /**
   * The submitter recorded by Gerrit (the submit approval of the merged patch set), used when the
   * merge event itself was missed. 0 if unknown.
   */
  private int storedSubmitter(ChangeNotes notes) {
    PatchSetApproval a = approvalsUtil.getSubmitter(notes, notes.getChange().currentPatchSetId());
    return a == null ? 0 : a.accountId().get();
  }

  /**
   * Runs the ref operation validators Gerrit applies when the submitter deletes {@code ref}
   * themself. Returns the rejection message, or {@code null} if the deletion is allowed.
   */
  private String deletionRejected(
      Project.NameKey project, String ref, ObjectId old, int submitter) {
    try {
      ProjectState state = projectCache.get(project).orElse(null);
      if (state == null) {
        return "project not found";
      }
      refValidatorsFactory
          .create(
              state.getProject(),
              userFactory.create(Account.id(submitter)),
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

  private boolean mayDelete(Project.NameKey project, String ref, int submitter) {
    if (submitter == 0) {
      return false;
    }
    try {
      return permissionBackend
          .absentUser(Account.id(submitter))
          .project(project)
          .ref(ref)
          .test(RefPermission.DELETE);
    } catch (PermissionBackendException e) {
      log.atWarning().withCause(e).log("cannot check delete permission on %s", ref);
      return false;
    }
  }
}
