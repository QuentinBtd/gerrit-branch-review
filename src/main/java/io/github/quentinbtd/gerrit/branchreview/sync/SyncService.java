// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.sync;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Striped;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.git.LockFailureException;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.Sequences;
import com.google.gerrit.server.change.ChangeInserter;
import com.google.gerrit.server.change.PatchSetInserter;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidators;
import com.google.gerrit.server.git.validators.RefOperationValidationException;
import com.google.gerrit.server.git.validators.RefOperationValidators;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.patch.DiffOperationsForCommitValidation;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.ChangeContext;
import com.google.gerrit.server.update.RepoView;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.Metrics;
import io.github.quentinbtd.gerrit.branchreview.compat.Validators;
import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import io.github.quentinbtd.gerrit.branchreview.config.ProjectConfigLoader;
import io.github.quentinbtd.gerrit.branchreview.config.ServerSettings;
import io.github.quentinbtd.gerrit.branchreview.squash.SquashPlanner;
import io.github.quentinbtd.gerrit.branchreview.state.ChangeLink;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle.SyncState;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore.ConcurrentUpdateException;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore.Loaded;
import io.github.quentinbtd.gerrit.branchreview.text.CommitMessage;
import io.github.quentinbtd.gerrit.branchreview.text.MessageGenerator;
import io.github.quentinbtd.gerrit.branchreview.text.TextOwnership;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * Synchronizes one source branch to its review Change.
 *
 * <p>Consistency model (see docs/architecture.md): the source ref update has already been committed
 * by Gerrit's normal receive path before this runs. This service then
 *
 * <ol>
 *   <li>reserves the Change number / patch set number and the synthetic commit in the lifecycle
 *       state ref (compare-and-swap),
 *   <li>inserts the Change or patch set through Gerrit's {@link BatchUpdate} (normal validation,
 *       NoteDb, indexing, events, notifications), and
 *   <li>records completion (compare-and-swap).
 * </ol>
 *
 * A crash between 1 and 3 leaves a {@link Lifecycle.Pending} reservation that is resolved against
 * NoteDb (the authoritative source) before any further work, so a Change is never created twice.
 * All work for a (project, ref) pair is serialized by an in-process lock; the state-ref CAS
 * protects against lost updates if that assumption is violated.
 */
@Singleton
public class SyncService {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();
  private static final int MAX_ATTEMPTS = 3;

  public enum Trigger {
    PUSH,
    /** A patch set was uploaded in Gerrit (e.g. an inline edit). */
    EDIT,
    RECOVERY,
    MANUAL
  }

  /** Explicit repair options; all false for ordinary pushes. */
  public record Options(
      boolean acceptDivergence, boolean newLifecycle, boolean resume, boolean resetText) {
    public static final Options NONE = new Options(false, false, false, false);
  }

  private final GitRepositoryManager repoManager;
  private final ProjectCache projectCache;
  private final ProjectConfigLoader configLoader;
  private final ServerSettings settings;
  private final StateStore store;
  private final PermissionBackend permissionBackend;
  private final ChangeNotes.Factory notesFactory;
  private final ChangeInserter.Factory changeInserterFactory;
  private final PatchSetInserter.Factory patchSetInserterFactory;
  private final BatchUpdate.Factory batchUpdateFactory;
  private final Sequences sequences;
  private final DynamicItem<UrlFormatter> urlFormatter;
  private final Metrics metrics;
  private final CommandHints hints;
  private final IdentifiedUser.GenericFactory userFactory;
  private final GitReferenceUpdated gitRefUpdated;
  private final CommitValidators.Factory commitValidatorsFactory;
  private final RefOperationValidators.Factory refValidatorsFactory;
  private final DiffOperationsForCommitValidation.Factory diffOperationsFactory;
  private final Striped<Lock> locks = Striped.lazyWeakLock(1024);

  @Inject
  SyncService(
      GitRepositoryManager repoManager,
      ProjectCache projectCache,
      ProjectConfigLoader configLoader,
      ServerSettings settings,
      StateStore store,
      PermissionBackend permissionBackend,
      ChangeNotes.Factory notesFactory,
      ChangeInserter.Factory changeInserterFactory,
      PatchSetInserter.Factory patchSetInserterFactory,
      BatchUpdate.Factory batchUpdateFactory,
      Sequences sequences,
      DynamicItem<UrlFormatter> urlFormatter,
      Metrics metrics,
      CommandHints hints,
      IdentifiedUser.GenericFactory userFactory,
      GitReferenceUpdated gitRefUpdated,
      CommitValidators.Factory commitValidatorsFactory,
      RefOperationValidators.Factory refValidatorsFactory,
      DiffOperationsForCommitValidation.Factory diffOperationsFactory) {
    this.commitValidatorsFactory = commitValidatorsFactory;
    this.refValidatorsFactory = refValidatorsFactory;
    this.diffOperationsFactory = diffOperationsFactory;
    this.hints = hints;
    this.userFactory = userFactory;
    this.gitRefUpdated = gitRefUpdated;
    this.repoManager = repoManager;
    this.projectCache = projectCache;
    this.configLoader = configLoader;
    this.settings = settings;
    this.store = store;
    this.permissionBackend = permissionBackend;
    this.notesFactory = notesFactory;
    this.changeInserterFactory = changeInserterFactory;
    this.patchSetInserterFactory = patchSetInserterFactory;
    this.batchUpdateFactory = batchUpdateFactory;
    this.sequences = sequences;
    this.urlFormatter = urlFormatter;
    this.metrics = metrics;
  }

  /** The lock serializing all plugin work on one source branch (sync, cleanup, repair). */
  public Lock lockFor(Project.NameKey project, String ref) {
    return locks.get(project.get() + '\0' + ref);
  }

  public SyncResult sync(
      Project.NameKey project,
      String sourceRef,
      IdentifiedUser user,
      Trigger trigger,
      Options opts) {
    SyncResult r = syncUnmetered(project, sourceRef, user, trigger, opts);
    if (r.kind() != SyncResult.Kind.IGNORED) {
      metrics.sync(r.kind().metric());
      log.atInfo().log(
          "branch-review sync project=%s ref=%s actor=%d trigger=%s result=%s change=%d ps=%d",
          project,
          sourceRef,
          user.getAccountId().get(),
          trigger,
          r.kind(),
          r.changeNumber(),
          r.patchSet());
    }
    return r;
  }

  private SyncResult syncUnmetered(
      Project.NameKey project,
      String sourceRef,
      IdentifiedUser user,
      Trigger trigger,
      Options opts) {
    if (settings.emergencyDisabled()) {
      return SyncResult.ignored(sourceRef);
    }
    BranchReviewConfig cfg = configLoader.load(project);
    if (!cfg.isEnabled()) {
      if (cfg.status() == BranchReviewConfig.Status.INVALID
          && BranchReviewConfig.isOrdinaryBranch(sourceRef)) {
        return SyncResult.of(
            SyncResult.Kind.NOT_SYNCHRONIZED,
            sourceRef,
            "branch-review.config on refs/meta/config is invalid: "
                + String.join("; ", cfg.errors()));
      }
      return SyncResult.ignored(sourceRef);
    }
    Lock lock = lockFor(project, sourceRef);
    boolean locked;
    try {
      locked = lock.tryLock(settings.pushWaitSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      locked = false;
    }
    if (!locked) {
      return SyncResult.of(
          SyncResult.Kind.IN_PROGRESS,
          sourceRef,
          "another synchronization of this branch is running; check status later");
    }
    try {
      for (int attempt = 1; ; attempt++) {
        try {
          return attempt(project, sourceRef, cfg, user, trigger, opts);
        } catch (ConcurrentUpdateException | LockFailureException e) {
          if (attempt >= MAX_ATTEMPTS) {
            return recordFailure(project, sourceRef, user, e);
          }
          log.atFine().withCause(e).log("retrying %s %s", project, sourceRef);
        } catch (IOException | PermissionBackendException | RuntimeException e) {
          log.atSevere().withCause(e).log("sync of %s %s failed", project, sourceRef);
          return recordFailure(project, sourceRef, user, e);
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private SyncResult attempt(
      Project.NameKey project,
      String sourceRef,
      BranchReviewConfig cfg,
      IdentifiedUser user,
      Trigger trigger,
      Options opts)
      throws IOException, ConcurrentUpdateException, PermissionBackendException {
    try (Repository repo = repoManager.openRepository(project);
        ObjectInserter ins = repo.newObjectInserter();
        RevWalk rw = new RevWalk(ins.newReader())) {
      Loaded<Lifecycle> loaded = store.loadLifecycle(repo, sourceRef);
      Lifecycle lc = loaded.value();
      ObjectId stateId = loaded.refId();
      if (lc != null && lc.pending != null) {
        stateId = resolvePending(repo, project, lc, stateId);
      }

      boolean eligible = cfg.isEligibleSource(sourceRef);
      if (!eligible) {
        if (lc != null && lc.isActive() && lc.changeNumber > 0) {
          return SyncResult.of(
              SyncResult.Kind.NOT_SYNCHRONIZED,
              sourceRef,
              "branch no longer matches branch-review.config patterns; Change "
                  + lc.changeNumber
                  + " was not updated");
        }
        return SyncResult.ignored(sourceRef);
      }

      Ref src = repo.exactRef(sourceRef);
      if (src == null || src.getObjectId() == null) {
        if (lc != null && lc.isActive() && lc.syncState != SyncState.SOURCE_DELETED) {
          lc.syncState = SyncState.SOURCE_DELETED;
          lc.observedSourceId = null;
          lc.lastActor = user.getAccountId().get();
          store.saveLifecycle(repo, project, lc, stateId, "source deleted");
          return SyncResult.of(
              SyncResult.Kind.SOURCE_DELETED,
              sourceRef,
              visibleChange(project, lc.changeNumber, user)
                  .map(n -> "Change " + n + " is kept; it no longer tracks a live branch")
                  .orElse("its review Change is kept; it no longer tracks a live branch"));
        }
        return SyncResult.ignored(sourceRef);
      }
      ObjectId sourceId = src.getObjectId();

      ChangeNotes notes = null;
      if (lc != null && lc.isActive() && lc.hasChange()) {
        notes = loadNotes(project, lc.changeNumber).orElse(null);
        if (notes == null || !notes.getChange().getKey().get().equals(lc.changeKey)) {
          lc.syncState = SyncState.BLOCKED;
          lc.lastError = "mapped Change " + lc.changeNumber + " is missing or has a different key";
          store.saveLifecycle(repo, project, lc, stateId, "inconsistent mapping");
          return SyncResult.of(
              SyncResult.Kind.NOT_SYNCHRONIZED,
              sourceRef,
              "the review Change of this branch is missing or inconsistent; automatic"
                  + " synchronization stopped, use the repair procedure");
        }
        if (!notes.getChange().isMerged()
            && !permissionBackend.user(user).change(notes).test(ChangePermission.READ)) {
          // Nothing about a Change the user cannot see may be revealed (number, URL, target).
          return SyncResult.of(
              SyncResult.Kind.NOT_SYNCHRONIZED,
              sourceRef,
              "the review Change of this branch is not visible to you; it was not updated");
        }
        switch (notes.getChange().getStatus()) {
          case MERGED -> {
            markMerged(lc, notes);
            stateId = store.saveLifecycle(repo, project, lc, stateId, "change merged");
            notes = null;
          }
          case ABANDONED -> {
            if (!opts.newLifecycle()) {
              return SyncResult.of(
                      SyncResult.Kind.NOT_SYNCHRONIZED,
                      sourceRef,
                      "Change "
                          + lc.changeNumber
                          + " is abandoned and is not restored automatically.",
                      "Restore it in Gerrit and push again (or run branch-review sync), or start a"
                          + " new Change with: "
                          + hints.sync(project, sourceRef, "--new-lifecycle"))
                  .withChange(
                      lc.changeNumber,
                      lc.lastSyntheticPatchSet,
                      false,
                      changeUrl(project, lc.changeNumber));
            }
            lc.status = Lifecycle.Status.CLOSED;
            stateId = store.saveLifecycle(repo, project, lc, stateId, "close abandoned lifecycle");
            notes = null;
          }
          case NEW -> {}
        }
      }

      if (lc != null && lc.isActive() && lc.hasChange()) {
        if (lc.syncState == SyncState.SOURCE_DELETED && !opts.resume()) {
          return SyncResult.of(
                  SyncResult.Kind.NOT_SYNCHRONIZED,
                  sourceRef,
                  "this branch was deleted and recreated; Change "
                      + lc.changeNumber
                      + " belongs to the previous branch.",
                  "Abandon it and push again to start a new Change, or continue it explicitly with:"
                      + " "
                      + hints.sync(project, sourceRef, "--resume"))
              .withChange(
                  lc.changeNumber,
                  lc.lastSyntheticPatchSet,
                  false,
                  changeUrl(project, lc.changeNumber));
        }
        if (opts.newLifecycle()) {
          return SyncResult.of(
              SyncResult.Kind.NOT_SYNCHRONIZED,
              sourceRef,
              "Change "
                  + lc.changeNumber
                  + " is still open; abandon it before starting a new lifecycle");
        }
        return update(
            repo, ins, rw, project, cfg, lc, stateId, notes, sourceId, user, trigger, opts);
      }
      return create(repo, ins, rw, project, sourceRef, cfg, lc, stateId, sourceId, user);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Creation

  private SyncResult create(
      Repository repo,
      ObjectInserter ins,
      RevWalk rw,
      Project.NameKey project,
      String sourceRef,
      BranchReviewConfig cfg,
      Lifecycle previous,
      ObjectId stateId,
      ObjectId sourceId,
      IdentifiedUser user)
      throws IOException, ConcurrentUpdateException, PermissionBackendException {
    String target = cfg.targetBranch();
    Ref targetRef = repo.exactRef(target);
    if (targetRef == null || targetRef.getObjectId() == null) {
      return SyncResult.of(
          SyncResult.Kind.NOT_SYNCHRONIZED,
          sourceRef,
          "target branch " + target + " does not exist");
    }
    ObjectId targetId = targetRef.getObjectId();

    // Branch reuse after a squash submit: the submitted commit must be part of the new history,
    // otherwise the old (already submitted) work would be reviewed and submitted again.
    String mergedCommit =
        previous == null
            ? null
            : previous.status == Lifecycle.Status.MERGED && previous.merge != null
                ? previous.merge.mergedCommit
                : previous.previousMergedCommit;
    if (mergedCommit != null) {
      ObjectId merged = ObjectId.fromString(mergedCommit);
      boolean reachable;
      try {
        reachable = rw.isMergedInto(rw.parseCommit(merged), rw.parseCommit(sourceId));
      } catch (MissingObjectException e) {
        reachable = false;
      }
      if (!reachable) {
        int prevChange =
            previous.status == Lifecycle.Status.MERGED
                ? previous.changeNumber
                : previous.previousChangeNumber;
        return SyncResult.of(
            SyncResult.Kind.NOT_SYNCHRONIZED,
            sourceRef,
            "this branch was already submitted"
                + visibleChange(project, prevChange, user).map(n -> " as Change " + n).orElse("")
                + ".",
            "To continue on the same branch name, start it from the current target, e.g.:",
            "  git fetch origin && git switch -C <branch> origin/"
                + target.substring("refs/heads/".length())
                + " && git push --force origin <branch>",
            "or merge " + target + " into the branch, then push again.");
      }
    }

    ProjectState projectState =
        projectCache.get(project).orElseThrow(() -> new IOException("project gone"));
    try {
      projectState.checkStatePermitsWrite();
      PermissionBackend.ForRef perm = permissionBackend.user(user).project(project).ref(target);
      perm.check(RefPermission.READ);
      perm.check(RefPermission.CREATE_CHANGE);
    } catch (AuthException | ResourceConflictException e) {
      return SyncResult.of(
          SyncResult.Kind.NOT_SYNCHRONIZED,
          sourceRef,
          "you are not allowed to create a review for " + target + ": " + e.getMessage());
    }

    SquashPlanner.Plan plan =
        SquashPlanner.plan(
            repo, ins, sourceId, targetId, new SquashPlanner.Limits(settings.maxCommits()));
    if (!plan.ok()) {
      return SyncResult.of(
          SyncResult.Kind.NOT_SYNCHRONIZED, sourceRef, explain(plan, target, true));
    }

    Lifecycle lc;
    if (previous != null && previous.isActive() && previous.changeNumber == 0) {
      lc = previous; // An earlier creation attempt failed definitively; keep its identity.
    } else {
      lc = new Lifecycle();
      lc.id = UUID.randomUUID().toString();
      lc.project = project.get();
      lc.sourceRef = sourceRef;
      lc.createdOn = Instant.now().toString();
      if (previous != null) {
        lc.previousLifecycleId = previous.id;
        lc.previousChangeNumber = previous.changeNumber;
        lc.previousMergedCommit = mergedCommit;
      }
    }
    lc.targetRef = target;
    lc.changeKey = changeKeyFor(project, sourceRef, lc.id);
    lc.owner = user.getAccountId().get();
    lc.lastActor = user.getAccountId().get();

    MessageGenerator.Generated gen =
        MessageGenerator.generate(
            sourceRef, cfg.matchingPattern(sourceRef).orElse(null), plan.commits());
    TextOwnership.Result text =
        TextOwnership.next(null, TextOwnership.State.initial(), gen, lc.changeKey);
    ObjectId commit = insertCommit(ins, rw, plan, text.message(), user, null);
    ins.flush();

    int number = sequences.nextChangeId();
    ChangeLink link = new ChangeLink();
    link.lifecycleId = lc.id;
    link.sourceRef = sourceRef;
    link.changeKey = lc.changeKey;
    store.saveLink(repo, project, number, link, ObjectId.zeroId());

    lc.pending = pending("create", number, 1, commit, plan, text.state());
    lc.syncState = SyncState.PENDING;
    lc.observedSourceId = sourceId.name();
    stateId = store.saveLifecycle(repo, project, lc, stateId, "reserve change " + number);

    try (RefUpdateContext ctx = RefUpdateContext.open(RefUpdateType.CHANGE_MODIFICATION);
        BatchUpdate bu = batchUpdateFactory.create(project, user, TimeUtil.now())) {
      bu.setRepository(repo, rw, ins);
      ChangeInserter ci =
          changeInserterFactory
              .create(Change.id(number), commit, target)
              .setWorkInProgress(true)
              .setPatchSetDescription(describe(sourceRef, plan))
              .setMessage("Uploaded patch set 1: synchronized from branch (branch-review).");
      bu.insertChange(ci);
      bu.execute();
    } catch (RestApiException | UpdateException e) {
      return afterInsertFailure(repo, project, lc, stateId, e);
    }
    finalizePending(lc);
    store.saveLifecycle(repo, project, lc, stateId, "created change " + number);
    return result(SyncResult.Kind.CREATED, project, lc, true);
  }

  // ---------------------------------------------------------------------------------------------
  // Update

  private SyncResult update(
      Repository repo,
      ObjectInserter ins,
      RevWalk rw,
      Project.NameKey project,
      BranchReviewConfig cfg,
      Lifecycle lc,
      ObjectId stateId,
      ChangeNotes notes,
      ObjectId sourceId,
      IdentifiedUser user,
      Trigger trigger,
      Options opts)
      throws IOException, ConcurrentUpdateException, PermissionBackendException {
    List<String> extra = new ArrayList<>();
    SyncResult r =
        doUpdate(
            repo, ins, rw, project, cfg, lc, stateId, notes, sourceId, user, trigger, opts, extra);
    return extra.isEmpty() ? r : r.withExtraDetails(extra);
  }

  private SyncResult doUpdate(
      Repository repo,
      ObjectInserter ins,
      RevWalk rw,
      Project.NameKey project,
      BranchReviewConfig cfg,
      Lifecycle lc,
      ObjectId stateId,
      ChangeNotes notes,
      ObjectId sourceId,
      IdentifiedUser user,
      Trigger trigger,
      Options opts,
      List<String> extra)
      throws IOException, ConcurrentUpdateException, PermissionBackendException {
    String sourceRef = lc.sourceRef;
    lc.lastActor = user.getAccountId().get();
    if (!cfg.targetBranch().equals(lc.targetRef)) {
      lc.syncState = SyncState.BLOCKED;
      lc.observedSourceId = sourceId.name();
      lc.lastError = "configured target changed from " + lc.targetRef + " to " + cfg.targetBranch();
      store.saveLifecycle(repo, project, lc, stateId, "target changed");
      return SyncResult.of(
              SyncResult.Kind.NOT_SYNCHRONIZED,
              sourceRef,
              lc.lastError
                  + "; Change "
                  + lc.changeNumber
                  + " keeps targeting "
                  + lc.targetRef
                  + ".",
              "Restore the previous targetBranch, or abandon the Change and run"
                  + " branch-review sync --new-lifecycle to review against the new target.")
          .withChange(
              lc.changeNumber,
              lc.lastSyntheticPatchSet,
              notes.getChange().isWorkInProgress(),
              changeUrl(project, lc.changeNumber));
    }

    String changeDest = notes.getChange().getDest().branch();
    if (!changeDest.equals(lc.targetRef)) {
      // Someone moved the Change: patch sets computed against the lifecycle target would be
      // delivered to another branch.
      lc.syncState = SyncState.BLOCKED;
      lc.observedSourceId = sourceId.name();
      lc.lastError =
          "Change " + lc.changeNumber + " was moved from " + lc.targetRef + " to " + changeDest;
      store.saveLifecycle(repo, project, lc, stateId, "change moved");
      return SyncResult.of(
              SyncResult.Kind.NOT_SYNCHRONIZED,
              sourceRef,
              lc.lastError + "; automatic synchronization stopped.",
              "Move it back to "
                  + lc.targetRef
                  + ", or abandon it and start a new Change with: "
                  + hints.sync(project, sourceRef, "--new-lifecycle"))
          .withChange(
              lc.changeNumber,
              notes.getCurrentPatchSet().number(),
              notes.getChange().isWorkInProgress(),
              changeUrl(project, lc.changeNumber));
    }

    try {
      permissionBackend.user(user).change(notes).check(ChangePermission.READ);
      permissionBackend.user(user).change(notes).check(ChangePermission.ADD_PATCH_SET);
    } catch (AuthException e) {
      return SyncResult.of(
          SyncResult.Kind.NOT_SYNCHRONIZED,
          sourceRef,
          "you are not allowed to upload a patch set to the review Change of this branch");
    }

    PatchSet current = notes.getCurrentPatchSet();
    RevCommit currentCommit = rw.parseCommit(current.commitId());
    ObjectId lastSynthetic = ObjectId.fromString(lc.lastSyntheticCommit);
    if (!current.commitId().equals(lastSynthetic)
        && !isDerivative(repo, ins, rw, currentCommit, rw.parseCommit(lastSynthetic))
        && !opts.acceptDivergence()) {
      // A human changed the reviewed content in Gerrit (inline edit, direct upload).
      EditBackport backport =
          cfg.syncEditsToBranch()
              ? backportEdits(
                  repo,
                  ins,
                  rw,
                  project,
                  lc,
                  current,
                  currentCommit,
                  rw.parseCommit(lastSynthetic),
                  sourceId)
              : EditBackport.refused(null);
      if (!backport.accepted() && historyRewritten(rw, lc, sourceId)) {
        // The branch was force-pushed since the last synchronization: Gerrit already checked the
        // pusher's force permission, so the pushed content is authoritative over edits it does
        // not contain. They stay visible in the published patch set.
        extra.add(
            "the branch history was rewritten (force push) and does not contain the edits of"
                + " patch set "
                + current.number()
                + (backport.reason() == null ? "" : " (" + backport.reason() + ")")
                + "; the pushed content replaces them, they remain visible in patch set "
                + current.number());
        backport = EditBackport.alreadyInBranch();
      }
      if (backport.accepted()) {
        lc.lastSyntheticCommit = current.commitId().name();
        lc.lastSyntheticPatchSet = current.number();
        if (backport.commit() != null) {
          sourceId = backport.commit();
          extra.add(
              "applied the edits of patch set "
                  + current.number()
                  + " (by "
                  + backport.editor()
                  + ") to "
                  + sourceRef
                  + " as "
                  + backport.commit().abbreviate(12).name()
                  + "; run git pull before pushing again");
        }
      } else {
        lc.syncState = SyncState.DIVERGED;
        lc.observedSourceId = sourceId.name();
        lc.lastError =
            "patch set "
                + current.number()
                + " was not produced by branch-review and changes the reviewed content";
        store.saveLifecycle(repo, project, lc, stateId, "diverged");
        List<String> lines = new ArrayList<>();
        lines.add(
            lc.lastError + "; automatic synchronization is paused so that it is not overwritten.");
        if (backport.reason() != null) {
          lines.add("the edits could not be applied to the branch: " + backport.reason());
        }
        PatchSet.Id ours = PatchSet.id(notes.getChangeId(), lc.lastSyntheticPatchSet);
        lines.add("To bring the edits into your branch:");
        lines.add("  git fetch origin " + ours.toRefName() + " " + current.id().toRefName());
        lines.add(
            "  git diff "
                + lastSynthetic.name()
                + " "
                + current.commitId().name()
                + " | git apply --3way");
        if (cfg.syncEditsToBranch()) {
          lines.add(
              "then commit and push; synchronization resumes once the branch contains the edits.");
          lines.add(
              "Or keep the branch as it is (the edits stay visible in patch set "
                  + current.number()
                  + "): "
                  + hints.sync(project, sourceRef, "--accept-divergence"));
        } else {
          lines.add(
              "then commit, push, and run: "
                  + hints.sync(project, sourceRef, "--accept-divergence"));
        }
        return SyncResult.of(
                SyncResult.Kind.NOT_SYNCHRONIZED, sourceRef, lines.toArray(new String[0]))
            .withChange(
                lc.changeNumber,
                current.number(),
                notes.getChange().isWorkInProgress(),
                changeUrl(project, lc.changeNumber));
      }
    }

    // Nothing changed on the source side. An explicit repair still recomputes when it accepts a
    // divergence (the human patch set must be replaced) or, for a manual sync, when the target
    // moved (so that the Change can be refreshed without a dummy source commit).
    Ref currentTarget = repo.exactRef(lc.targetRef);
    boolean targetMoved =
        trigger == Trigger.MANUAL
            && currentTarget != null
            && !currentTarget.getObjectId().name().equals(lc.lastTargetId);
    if (sourceId.name().equals(lc.lastSourceId)
        && lc.syncState == SyncState.SYNCED
        && !opts.resetText()
        && !opts.acceptDivergence()
        && !targetMoved) {
      return result(
          SyncResult.Kind.NOOP,
          project,
          lc,
          notes.getChange().isWorkInProgress(),
          current.number());
    }

    Ref targetRef = repo.exactRef(lc.targetRef);
    if (targetRef == null || targetRef.getObjectId() == null) {
      lc.syncState = SyncState.BLOCKED;
      lc.observedSourceId = sourceId.name();
      lc.lastError = "target branch " + lc.targetRef + " does not exist";
      store.saveLifecycle(repo, project, lc, stateId, "target missing");
      return SyncResult.of(SyncResult.Kind.NOT_SYNCHRONIZED, sourceRef, lc.lastError);
    }
    SquashPlanner.Plan plan =
        SquashPlanner.plan(
            repo,
            ins,
            sourceId,
            targetRef.getObjectId(),
            new SquashPlanner.Limits(settings.maxCommits()));
    if (!plan.ok()) {
      lc.syncState =
          switch (plan.outcome()) {
            case EMPTY -> SyncState.EMPTY;
            case CONFLICT -> SyncState.CONFLICT;
            default -> SyncState.UNSUPPORTED;
          };
      lc.observedSourceId = sourceId.name();
      lc.lastError = String.join(" ", explain(plan, lc.targetRef, false));
      store.saveLifecycle(repo, project, lc, stateId, "not synchronized: " + plan.outcome());
      String[] lines = explain(plan, lc.targetRef, false);
      String[] all = Arrays.copyOf(lines, lines.length + 1);
      all[lines.length] =
          "patch set "
              + current.number()
              + " of Change "
              + lc.changeNumber
              + " no longer represents this branch and cannot be submitted until it is"
              + " synchronized";
      return SyncResult.of(SyncResult.Kind.NOT_SYNCHRONIZED, sourceRef, all)
          .withChange(
              lc.changeNumber,
              current.number(),
              notes.getChange().isWorkInProgress(),
              changeUrl(project, lc.changeNumber));
    }

    // Equivalent content on the same base: record the association without new history.
    if (plan.treeId().equals(currentCommit.getTree())
        && currentCommit.getParentCount() == 1
        && currentCommit.getParent(0).equals(plan.targetId())
        && !opts.resetText()) {
      lc.lastSourceId = sourceId.name();
      lc.observedSourceId = sourceId.name();
      lc.lastTargetId = plan.targetId().name();
      lc.lastSyntheticCommit = current.commitId().name();
      lc.lastSyntheticPatchSet = current.number();
      lc.syncState = SyncState.SYNCED;
      lc.lastError = null;
      lc.attempts = 0;
      store.saveLifecycle(repo, project, lc, stateId, "equivalent content");
      return result(
          SyncResult.Kind.NOOP,
          project,
          lc,
          notes.getChange().isWorkInProgress(),
          current.number());
    }

    MessageGenerator.Generated gen =
        MessageGenerator.generate(
            sourceRef, cfg.matchingPattern(sourceRef).orElse(null), plan.commits());
    TextOwnership.State prior =
        opts.resetText() ? TextOwnership.State.initial() : lc.text.toState();
    String published = currentCommit.getFullMessage();
    if (opts.resetText()) {
      // Reset keeps user trailers but takes the generated subject and body again.
      published = null;
    }
    TextOwnership.Result text = TextOwnership.next(published, prior, gen, lc.changeKey);
    if (opts.resetText()) {
      text =
          new TextOwnership.Result(
              withFooters(text.message(), currentCommit.getFullMessage(), lc.changeKey),
              text.state());
    }
    ObjectId commit = insertCommit(ins, rw, plan, text.message(), user, null);
    // Going back to the content of an older patch set (e.g. after accepting a divergence): a patch
    // set must not reuse an earlier patch set's commit, so move the committer time forward until
    // the commit is new (identities have one-second resolution).
    Set<ObjectId> used = new HashSet<>();
    notes.getPatchSets().values().forEach(ps -> used.add(ps.commitId()));
    used.remove(current.commitId());
    Instant when = TimeUtil.now();
    while (used.contains(commit)) {
      commit = insertCommit(ins, rw, plan, text.message(), user, when);
      when = when.plusSeconds(1);
    }
    ins.flush();
    if (commit.equals(current.commitId())) {
      lc.lastSourceId = sourceId.name();
      lc.observedSourceId = sourceId.name();
      lc.syncState = SyncState.SYNCED;
      store.saveLifecycle(repo, project, lc, stateId, "identical commit");
      return result(
          SyncResult.Kind.NOOP,
          project,
          lc,
          notes.getChange().isWorkInProgress(),
          current.number());
    }

    PatchSet.Id psId = ChangeUtil.nextPatchSetId(repo, current.id());
    lc.pending = pending("patchset", lc.changeNumber, psId.get(), commit, plan, text.state());
    lc.syncState = SyncState.PENDING;
    lc.observedSourceId = sourceId.name();
    stateId = store.saveLifecycle(repo, project, lc, stateId, "reserve patch set " + psId.get());

    try (RefUpdateContext ctx = RefUpdateContext.open(RefUpdateType.CHANGE_MODIFICATION);
        BatchUpdate bu = batchUpdateFactory.create(project, user, TimeUtil.now())) {
      bu.setRepository(repo, rw, ins);
      bu.addOp(notes.getChangeId(), new ExpectCurrentPatchSet(current.id()));
      PatchSetInserter psi =
          patchSetInserterFactory
              .create(notes, psId, commit)
              .setDescription(describe(sourceRef, plan))
              .setMessage(
                  "Uploaded patch set "
                      + psId.get()
                      + ": synchronized from branch (branch-review).");
      bu.addOp(notes.getChangeId(), psi);
      bu.execute();
    } catch (RestApiException | UpdateException e) {
      return afterInsertFailure(repo, project, lc, stateId, e);
    }
    finalizePending(lc);
    store.saveLifecycle(repo, project, lc, stateId, "patch set " + psId.get());
    ChangeNotes after = notesFactory.createChecked(project, notes.getChangeId());
    return result(SyncResult.Kind.UPDATED, project, lc, after.getChange().isWorkInProgress());
  }

  /** Fails the batch if a patch set was added or the change closed since it was read. */
  private static final class ExpectCurrentPatchSet implements BatchUpdateOp {
    private final PatchSet.Id expected;

    ExpectCurrentPatchSet(PatchSet.Id expected) {
      this.expected = expected;
    }

    @Override
    public boolean updateChange(ChangeContext ctx) throws ResourceConflictException {
      Change c = ctx.getChange();
      if (!c.currentPatchSetId().equals(expected) || !c.isNew()) {
        throw new ResourceConflictException(
            "Change " + c.getId() + " was modified concurrently; synchronization will be retried");
      }
      return false;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Reservation handling

  private static Lifecycle.Pending pending(
      String op,
      int change,
      int ps,
      ObjectId commit,
      SquashPlanner.Plan plan,
      TextOwnership.State text) {
    Lifecycle.Pending p = new Lifecycle.Pending();
    p.op = op;
    p.changeNumber = change;
    p.patchSet = ps;
    p.commit = commit.name();
    p.sourceId = plan.sourceId().name();
    p.targetId = plan.targetId().name();
    p.startedOn = Instant.now().toString();
    p.text = new Lifecycle.Text();
    p.text.apply(text);
    return p;
  }

  private static void finalizePending(Lifecycle lc) {
    Lifecycle.Pending p = lc.pending;
    lc.changeNumber = p.changeNumber;
    lc.lastSyntheticCommit = p.commit;
    lc.lastSyntheticPatchSet = p.patchSet;
    lc.lastSourceId = p.sourceId;
    lc.lastTargetId = p.targetId;
    if (p.text != null) {
      lc.text = p.text;
    }
    lc.syncState = SyncState.SYNCED;
    lc.pending = null;
    lc.lastError = null;
    lc.attempts = 0;
    lc.nextAttemptAfter = null;
  }

  /**
   * Resolves a reservation against NoteDb: if the Change/patch set exists with the reserved commit,
   * the reservation is completed; otherwise it is discarded. Never inserts anything.
   */
  ObjectId resolvePending(Repository repo, Project.NameKey project, Lifecycle lc, ObjectId stateId)
      throws IOException, ConcurrentUpdateException {
    Lifecycle.Pending p = lc.pending;
    Optional<ChangeNotes> notes = loadNotes(project, p.changeNumber);
    boolean done = false;
    if (notes.isPresent()) {
      if (!notes.get().getChange().getKey().get().equals(lc.changeKey)) {
        lc.syncState = SyncState.BLOCKED;
        lc.lastError = "reserved Change " + p.changeNumber + " exists with an unexpected Change-Id";
        lc.pending = null;
        return store.saveLifecycle(repo, project, lc, stateId, "inconsistent reservation");
      }
      PatchSet ps =
          notes.get().getPatchSets().get(PatchSet.id(Change.id(p.changeNumber), p.patchSet));
      done = ps != null && ps.commitId().name().equals(p.commit);
    }
    if (done) {
      finalizePending(lc);
      return store.saveLifecycle(
          repo, project, lc, stateId, "recovered " + p.op + " " + p.changeNumber);
    }
    lc.pending = null;
    if ("create".equals(p.op)) {
      lc.changeNumber = 0;
    }
    lc.syncState = SyncState.FAILED;
    return store.saveLifecycle(repo, project, lc, stateId, "discarded unconfirmed " + p.op);
  }

  private SyncResult afterInsertFailure(
      Repository repo, Project.NameKey project, Lifecycle lc, ObjectId stateId, Exception e)
      throws IOException, ConcurrentUpdateException {
    Throwable cause = e instanceof UpdateException && e.getCause() != null ? e.getCause() : e;
    if (cause instanceof LockFailureException) {
      // Resolve the reservation, then let the caller retry from fresh state.
      int reserved = lc.pending.changeNumber;
      resolvePending(repo, project, lc, stateId);
      throw new ConcurrentUpdateException("Change " + reserved);
    }
    if (cause instanceof ResourceConflictException rce
        && rce.getMessage() != null
        && rce.getMessage().contains("modified concurrently")) {
      resolvePending(repo, project, lc, stateId);
      throw new ConcurrentUpdateException("Change " + lc.changeNumber);
    }
    String sourceRef = lc.sourceRef;
    boolean definitive = cause instanceof RestApiException;
    int change = lc.pending.changeNumber;
    String op = lc.pending.op;
    stateId = resolvePending(repo, project, lc, stateId);
    if (lc.syncState == SyncState.SYNCED) {
      // The insertion succeeded; only a later step (e.g. indexing) failed.
      log.atWarning().withCause(e).log("post-insert failure for %s %s", project, sourceRef);
      return result(
          "create".equals(op) ? SyncResult.Kind.CREATED : SyncResult.Kind.UPDATED,
          project,
          lc,
          true);
    }
    lc.lastError = cause.getMessage();
    lc.attempts++;
    if (definitive) {
      lc.syncState = SyncState.FAILED;
      lc.attempts = Integer.MAX_VALUE / 2; // Not retried automatically: needs a new push or repair.
    }
    store.saveLifecycle(repo, project, lc, stateId, "insert failed");
    log.atWarning().withCause(e).log(
        "cannot %s change %d for %s %s", op, change, project, sourceRef);
    return SyncResult.of(
        definitive ? SyncResult.Kind.NOT_SYNCHRONIZED : SyncResult.Kind.FAILED,
        sourceRef,
        "Gerrit rejected the review update: " + cause.getMessage());
  }

  private SyncResult recordFailure(
      Project.NameKey project, String sourceRef, IdentifiedUser user, Exception e) {
    try (Repository repo = repoManager.openRepository(project)) {
      Loaded<Lifecycle> l = store.loadLifecycle(repo, sourceRef);
      Lifecycle lc = l.value();
      if (lc == null) {
        lc = new Lifecycle();
        lc.id = UUID.randomUUID().toString();
        lc.project = project.get();
        lc.sourceRef = sourceRef;
        lc.createdOn = Instant.now().toString();
      }
      if (lc.isActive()) {
        if (lc.syncState != SyncState.PENDING) {
          lc.syncState = SyncState.FAILED;
        }
        lc.lastError = String.valueOf(e.getMessage());
        lc.lastActor = user.getAccountId().get();
        lc.attempts++;
        lc.nextAttemptAfter =
            Instant.now().plusSeconds(Math.min(3600, 30L << Math.min(lc.attempts, 7))).toString();
        store.saveLifecycle(repo, project, lc, l.refId(), "record failure");
      }
    } catch (IOException | ConcurrentUpdateException | RuntimeException e2) {
      log.atSevere().withCause(e2).log("cannot record failure for %s %s", project, sourceRef);
    }
    return SyncResult.of(
        SyncResult.Kind.FAILED,
        sourceRef,
        "error: " + e.getMessage(),
        "the branch update itself was accepted; inspect with: " + hints.status(project, sourceRef));
  }

  private void markMerged(Lifecycle lc, ChangeNotes notes) {
    lc.status = Lifecycle.Status.MERGED;
    if (lc.merge == null) {
      lc.merge = new Lifecycle.Merge();
      lc.merge.cleanup = "none";
    }
    if (lc.merge.mergedCommit == null) {
      lc.merge.mergedCommit = notes.getCurrentPatchSet().commitId().name();
      lc.merge.submittedSourceId = lc.lastSourceId;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Edits made in Gerrit -> source branch

  /** Outcome of carrying human edits of the review back to the source branch. */
  record EditBackport(boolean accepted, ObjectId commit, String editor, String reason) {
    static EditBackport applied(ObjectId commit, String editor) {
      return new EditBackport(true, commit, editor, null);
    }

    static EditBackport alreadyInBranch() {
      return new EditBackport(true, null, null, null);
    }

    static EditBackport refused(String reason) {
      return new EditBackport(false, null, null, reason);
    }
  }

  /**
   * Runs the checks Gerrit applies to a direct push of {@code commit} to {@code ref} by {@code
   * user}: commit validators for branch pushes (including plugin validators) and ref operation
   * validators. Returns the rejection message, if any.
   */
  private Optional<String> validateBranchUpdate(
      Repository repo,
      ObjectInserter ins,
      RevWalk rw,
      Project.NameKey project,
      String ref,
      ObjectId oldId,
      ObjectId commit,
      IdentifiedUser user)
      throws IOException {
    ProjectState state =
        projectCache.get(project).orElseThrow(() -> new IOException("project gone: " + project));
    ReceiveCommand cmd = new ReceiveCommand(oldId, commit, ref, ReceiveCommand.Type.UPDATE);
    // RepoView holds a ref cache with its own reference on the repository: close it.
    try (RepoView view = new RepoView(repo, rw, ins);
        CommitReceivedEvent event =
            new CommitReceivedEvent(
                cmd,
                state.getProject(),
                ref,
                ImmutableListMultimap.of(),
                repo.getConfig(),
                rw.getObjectReader(),
                commit,
                user,
                diffOperationsFactory.create(view, ins))) {
      Validators.forBranchPush(
              commitValidatorsFactory,
              permissionBackend.user(user).project(project),
              BranchNameKey.create(project, ref),
              user,
              rw)
          .validate(event);
    } catch (CommitValidationException e) {
      return Optional.of(e.getFullMessage());
    }
    try {
      refValidatorsFactory
          .create(state.getProject(), user, cmd, ImmutableListMultimap.of())
          .validateForRefOperation();
    } catch (RefOperationValidationException e) {
      StringBuilder msg = new StringBuilder(String.valueOf(e.getMessage()));
      e.getMessages().forEach(m -> msg.append("; ").append(m.getMessage()));
      return Optional.of(msg.toString());
    }
    return Optional.empty();
  }

  /** Whether the source no longer descends from the last synchronized source state. */
  private static boolean historyRewritten(RevWalk rw, Lifecycle lc, ObjectId sourceId)
      throws IOException {
    if (lc.lastSourceId == null) {
      return false;
    }
    try {
      return !rw.isMergedInto(
          rw.parseCommit(ObjectId.fromString(lc.lastSourceId)), rw.parseCommit(sourceId));
    } catch (MissingObjectException e) {
      return true;
    }
  }

  /**
   * Applies the human delta of {@code published} to the source branch as one fast-forward commit.
   *
   * <p>The delta is isolated as the difference between the plugin's last patch set (rebased onto
   * the published patch set's parent if Gerrit rebased it) and the published patch set. It is
   * three-way merged onto the current source tip, so the branch keeps its own history and never
   * receives target changes. The commit is authored by the uploader of the published patch set, who
   * must be allowed to push to the source branch; the ref update is a compare-and-swap on the tip
   * that was read. If the branch already contains the edits nothing is written.
   */
  private EditBackport backportEdits(
      Repository repo,
      ObjectInserter ins,
      RevWalk rw,
      Project.NameKey project,
      Lifecycle lc,
      PatchSet ps,
      RevCommit published,
      RevCommit synthetic,
      ObjectId sourceId)
      throws IOException, ConcurrentUpdateException, PermissionBackendException {
    if (published.getParentCount() != 1 || synthetic.getParentCount() != 1) {
      return EditBackport.refused("patch set " + ps.number() + " is a merge commit");
    }
    ObjectId base = synthetic.getTree();
    if (!published.getParent(0).equals(synthetic.getParent(0))) {
      ThreeWayMerger rebase =
          (ThreeWayMerger) MergeStrategy.RESOLVE.newMerger(ins, repo.getConfig());
      rebase.setBase(synthetic.getParent(0));
      if (!rebase.merge(rw.parseCommit(published.getParent(0)), synthetic)) {
        return EditBackport.refused(
            "patch set " + ps.number() + " was also rebased and its edits cannot be isolated");
      }
      base = rebase.getResultTreeId();
    }
    RevCommit tip = rw.parseCommit(sourceId);
    ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(ins, repo.getConfig());
    merger.setBase(base);
    if (!merger.merge(tip, published)) {
      List<String> paths = new ArrayList<>(merger.getUnmergedPaths());
      if (merger.getFailingPaths() != null) {
        paths.addAll(merger.getFailingPaths().keySet());
      }
      return EditBackport.refused(
          "they conflict with the branch in "
              + String.join(", ", paths.stream().sorted().distinct().limit(20).toList()));
    }
    ObjectId tree = merger.getResultTreeId();
    if (tree.equals(tip.getTree())) {
      return EditBackport.alreadyInBranch();
    }
    IdentifiedUser editor = userFactory.create(ps.uploader());
    String editorName = editor.getLoggableName();
    if (!permissionBackend
        .user(editor)
        .project(project)
        .ref(lc.sourceRef)
        .test(RefPermission.UPDATE)) {
      return EditBackport.refused(
          editorName
              + ", who uploaded patch set "
              + ps.number()
              + ", may not push to "
              + lc.sourceRef);
    }
    PersonIdent ident = editor.newCommitterIdent(ps.createdOn(), ZoneId.systemDefault());
    CommitBuilder cb = new CommitBuilder();
    cb.setTreeId(tree);
    cb.setParentId(tip);
    cb.setAuthor(ident);
    cb.setCommitter(ident);
    cb.setEncoding(UTF_8);
    String url = changeUrl(project, lc.changeNumber);
    cb.setMessage(
        "Apply review edits from Change "
            + lc.changeNumber
            + " patch set "
            + ps.number()
            + "\n\nThese edits were made in Gerrit on patch set "
            + ps.number()
            + " of Change "
            + lc.changeNumber
            + (url == null ? "" : " (" + url + ")")
            + "\nand applied to "
            + lc.sourceRef
            + " by branch-review.\n");
    ObjectId commit = ins.insert(cb);
    ins.flush();
    Optional<String> rejected =
        validateBranchUpdate(repo, ins, rw, project, lc.sourceRef, tip, commit, editor);
    if (rejected.isPresent()) {
      return EditBackport.refused("the branch's validation rejected it: " + rejected.get());
    }
    RefUpdate ru = repo.updateRef(lc.sourceRef);
    ru.setExpectedOldObjectId(tip);
    ru.setNewObjectId(commit);
    ru.setRefLogIdent(ident);
    ru.setRefLogMessage(
        "branch-review: apply edits of change " + lc.changeNumber + " patch set " + ps.number(),
        false);
    RefUpdate.Result r;
    try (RefUpdateContext ctx = RefUpdateContext.open(RefUpdateType.BRANCH_MODIFICATION)) {
      r = ru.update();
    }
    switch (r) {
      case FAST_FORWARD -> {
        gitRefUpdated.fire(project, ru, editor.state());
        log.atInfo().log(
            "branch-review applied edits project=%s ref=%s change=%d ps=%d editor=%d commit=%s",
            project,
            lc.sourceRef,
            lc.changeNumber,
            ps.number(),
            ps.uploader().get(),
            commit.name());
        return EditBackport.applied(commit, editorName);
      }
      case LOCK_FAILURE, REJECTED -> throw new ConcurrentUpdateException(lc.sourceRef);
      default -> {
        return EditBackport.refused("the branch could not be updated (" + r + ")");
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  private ObjectId insertCommit(
      ObjectInserter ins,
      RevWalk rw,
      SquashPlanner.Plan plan,
      String message,
      IdentifiedUser user,
      @Nullable Instant when)
      throws IOException {
    RevCommit tip = rw.parseCommit(plan.sourceId());
    PersonIdent tipIdent = tip.getCommitterIdent();
    // Deterministic by default: the same source state, target and text yield the same commit.
    PersonIdent ident =
        user.newCommitterIdent(
            when != null ? when : tipIdent.getWhenAsInstant(), tipIdent.getZoneId());
    CommitBuilder cb = new CommitBuilder();
    cb.setTreeId(plan.treeId());
    cb.setParentId(plan.targetId());
    cb.setAuthor(ident);
    cb.setCommitter(ident);
    cb.setEncoding(UTF_8);
    cb.setMessage(message);
    return ins.insert(cb);
  }

  /**
   * Whether {@code current} is a published UI message edit or a pure Gerrit rebase of {@code
   * synthetic}: same tree and parents, or a clean rebase onto a different parent whose result is
   * exactly the published tree.
   */
  public static boolean isDerivative(
      Repository repo, ObjectInserter ins, RevWalk rw, RevCommit current, RevCommit synthetic)
      throws IOException {
    if (current.getParentCount() != 1 || synthetic.getParentCount() != 1) {
      return false;
    }
    if (current.getTree().equals(synthetic.getTree())
        && current.getParent(0).equals(synthetic.getParent(0))) {
      return true;
    }
    if (current.getParent(0).equals(synthetic.getParent(0))) {
      return false;
    }
    ThreeWayMerger m = (ThreeWayMerger) MergeStrategy.RESOLVE.newMerger(ins, repo.getConfig());
    m.setBase(synthetic.getParent(0));
    if (!m.merge(rw.parseCommit(current.getParent(0)), synthetic)) {
      return false;
    }
    return m.getResultTreeId().equals(current.getTree());
  }

  /** The generated subject and body with the trailers of the published message. */
  private static String withFooters(String generated, String published, String key) {
    CommitMessage gen = CommitMessage.parse(generated);
    ImmutableList.Builder<CommitMessage.Footer> footers = ImmutableList.builder();
    CommitMessage.parse(published).footers().stream()
        .filter(f -> !f.key().equalsIgnoreCase(CommitMessage.CHANGE_ID))
        .forEach(footers::add);
    footers.add(new CommitMessage.Footer(CommitMessage.CHANGE_ID, key));
    return new CommitMessage(gen.subject(), gen.body(), footers.build()).render();
  }

  private static String describe(String sourceRef, SquashPlanner.Plan plan) {
    return "branch-review: "
        + sourceRef
        + " @ "
        + plan.sourceId().abbreviate(12).name()
        + " squashed onto "
        + plan.targetId().abbreviate(12).name();
  }

  static String changeKeyFor(Project.NameKey project, String sourceRef, String lifecycleId) {
    return "I"
        + Hashing.sha1()
            .hashString(
                "branch-review\0" + project.get() + "\0" + sourceRef + "\0" + lifecycleId, UTF_8);
  }

  private static String[] explain(SquashPlanner.Plan plan, String target, boolean initial) {
    return switch (plan.outcome()) {
      case EMPTY ->
          new String[] {
            "the branch has no changes relative to "
                + target
                + (initial ? "; no Change was created" : "")
          };
      case CONFLICT ->
          new String[] {
            "the branch conflicts with "
                + target
                + " in: "
                + String.join(", ", plan.conflictPaths()),
            "merge or rebase " + target + " into the branch and push again"
          };
      case UNRELATED -> new String[] {"the branch shares no history with " + target};
      case TOO_LARGE ->
          new String[] {
            "the branch has more commits than the configured limit relative to " + target
          };
      case OK -> new String[0];
    };
  }

  private SyncResult result(
      SyncResult.Kind kind, Project.NameKey project, Lifecycle lc, boolean wip) {
    return result(kind, project, lc, wip, lc.lastSyntheticPatchSet);
  }

  private SyncResult result(
      SyncResult.Kind kind, Project.NameKey project, Lifecycle lc, boolean wip, int ps) {
    return SyncResult.of(kind, lc.sourceRef)
        .withChange(lc.changeNumber, ps, wip, changeUrl(project, lc.changeNumber));
  }

  /** {@code number} if the user can see that Change, empty otherwise. */
  private Optional<Integer> visibleChange(
      Project.NameKey project, int number, IdentifiedUser user) {
    if (number <= 0) {
      return Optional.empty();
    }
    return loadNotes(project, number)
        .filter(
            n -> {
              try {
                return permissionBackend.user(user).change(n).test(ChangePermission.READ);
              } catch (PermissionBackendException e) {
                return false;
              }
            })
        .map(n -> number);
  }

  private Optional<ChangeNotes> loadNotes(Project.NameKey project, int number) {
    try {
      return Optional.of(notesFactory.createChecked(project, Change.id(number)));
    } catch (NoSuchChangeException e) {
      return Optional.empty();
    }
  }

  private String changeUrl(Project.NameKey project, int number) {
    return urlFormatter.get().getChangeViewUrl(project, Change.id(number)).orElse(null);
  }
}
