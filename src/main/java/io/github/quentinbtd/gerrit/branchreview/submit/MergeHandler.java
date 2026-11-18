// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.submit;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.ChangeMergedListener;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.approval.ApprovalsUtil;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.notedb.ChangeNotes;
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
import org.eclipse.jgit.lib.Repository;

/**
 * Closes a lifecycle when its Change is merged and performs the optional source branch cleanup.
 *
 * <p>Cleanup runs only after Gerrit reports the merge, never on approval. The source ref is deleted
 * only if, at cleanup time, the project still enables {@code deleteSourceBranchOnSubmit}, the ref
 * is still an eligible source of this lifecycle, it still points at exactly the source commit
 * represented by the submitted patch set, and the submitting user may delete it ({@link
 * SourceBranchDeleter}: {@code delete} permission, ref operation validators, compare-and-swap on
 * that commit). A failed cleanup never affects the Submit and is retried a bounded number of times.
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
  private final ChangeNotes.Factory notesFactory;
  private final Metrics metrics;
  private final ApprovalsUtil approvalsUtil;
  private final IdentifiedUser.GenericFactory userFactory;
  private final SourceBranchDeleter deleter;

  @Inject
  MergeHandler(
      GitRepositoryManager repoManager,
      StateStore store,
      ProjectConfigLoader configLoader,
      ServerSettings settings,
      SyncService syncService,
      SyncExecutor executor,
      ChangeNotes.Factory notesFactory,
      Metrics metrics,
      ApprovalsUtil approvalsUtil,
      IdentifiedUser.GenericFactory userFactory,
      SourceBranchDeleter deleter) {
    this.userFactory = userFactory;
    this.deleter = deleter;
    this.approvalsUtil = approvalsUtil;
    this.repoManager = repoManager;
    this.store = store;
    this.configLoader = configLoader;
    this.settings = settings;
    this.syncService = syncService;
    this.executor = executor;
    this.notesFactory = notesFactory;
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
              && cfg.deleteSourceBranchOnSubmit(lc.sourceRef)
              && "none".equals(lc.merge.cleanup)) {
            lc.merge.cleanup = "pending";
          }
          changed = true;
        }
        if (changed) {
          stateId = store.saveLifecycle(repo, project, lc, stateId, "change " + number + " merged");
        }
        try {
          // The lifecycle has ended: its source message evidence is no longer consulted.
          store.deletePublications(repo, project, number, lc.id);
        } catch (IOException e) {
          log.atWarning().withCause(e).log(
              "cannot delete source message evidence of %s change %d", project, number);
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
    BranchReviewConfig cfg = configLoader.load(project);
    Ref src = repo.exactRef(lc.sourceRef);
    if (!cfg.isEnabled() || !cfg.deleteSourceBranchOnSubmit(lc.sourceRef)) {
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
    } else if (m.submitter == 0) {
      outcome = "skipped";
      detail = "submitter has no delete permission on the branch";
    } else {
      SourceBranchDeleter.Result r =
          deleter.delete(
              repo,
              project,
              lc.sourceRef,
              ObjectId.fromString(m.submittedSourceId),
              userFactory.create(Account.id(m.submitter)),
              "branch-review: delete source after submit of change " + lc.changeNumber);
      switch (r.outcome()) {
        case DELETED -> {
          outcome = "deleted";
          detail = "deleted " + lc.sourceRef + " at " + m.submittedSourceId;
        }
        case NO_PERMISSION -> {
          outcome = "skipped";
          detail = "submitter has no delete permission on the branch";
        }
        case REJECTED -> {
          outcome = "skipped";
          detail = "deletion rejected by validation: " + r.detail();
        }
        case CHANGED -> {
          outcome = "skipped";
          detail = "branch changed concurrently; kept";
        }
        default -> {
          outcome = "failed";
          detail = r.detail();
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
}
