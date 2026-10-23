// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.submit;

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.CodeReviewCommit.CodeReviewRevWalk;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.MergeValidationException;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.gerrit.server.git.validators.OnSubmitValidationListener;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.validators.ValidationException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import io.github.quentinbtd.gerrit.branchreview.config.ConfigParser;
import io.github.quentinbtd.gerrit.branchreview.config.ProjectConfigLoader;
import io.github.quentinbtd.gerrit.branchreview.config.ServerSettings;
import io.github.quentinbtd.gerrit.branchreview.state.ChangeLink;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncService;
import io.github.quentinbtd.gerrit.branchreview.validation.ConfigValidator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * Submit-time checks, run by Gerrit's normal Submit for every change about to be merged.
 *
 * <ul>
 *   <li>Changes targeting the plugin state namespace are rejected.
 *   <li>Changes to {@code refs/meta/config} with an invalid {@code branch-review.config} are
 *       rejected.
 *   <li>For Changes owned by a branch-review lifecycle of an enabled project:
 *       <ul>
 *         <li>the effective submit type must produce exactly one contribution commit: {@code
 *             FAST_FORWARD_ONLY}, {@code REBASE_IF_NECESSARY}, {@code REBASE_ALWAYS} and {@code
 *             CHERRY_PICK} are accepted; {@code MERGE_IF_NECESSARY} only when the target has not
 *             moved since the patch set was created (fast-forward); {@code MERGE_ALWAYS} never;
 *         <li>the lifecycle must be synchronized: the source branch must still point at the commit
 *             the current patch set was generated from (stale-source guard), and the patch set must
 *             be the generated one or a message-only edit / pure rebase of it.
 *       </ul>
 * </ul>
 *
 * The stale-source guard reads the mutable source ref; a push that lands after this check but
 * before the target update is not represented by the submitted content. Such a push finds the
 * lifecycle merged and is reported as not synchronized (see docs/architecture.md).
 */
@Singleton
public class SubmitGuard implements MergeValidationListener, OnSubmitValidationListener {
  private static final ImmutableSet<SubmitType> ONE_COMMIT_TYPES =
      ImmutableSet.of(
          SubmitType.FAST_FORWARD_ONLY,
          SubmitType.REBASE_IF_NECESSARY,
          SubmitType.REBASE_ALWAYS,
          SubmitType.CHERRY_PICK);

  private final StateStore store;
  private final ProjectConfigLoader configLoader;
  private final ServerSettings settings;
  private final ChangeData.Factory changeDataFactory;
  private final ConfigValidator configValidator;
  private final GitRepositoryManager repoManager;

  @Inject
  SubmitGuard(
      StateStore store,
      ProjectConfigLoader configLoader,
      ServerSettings settings,
      ChangeData.Factory changeDataFactory,
      ConfigValidator configValidator,
      GitRepositoryManager repoManager) {
    this.repoManager = repoManager;
    this.store = store;
    this.configLoader = configLoader;
    this.settings = settings;
    this.changeDataFactory = changeDataFactory;
    this.configValidator = configValidator;
  }

  @Override
  public void onPreMerge(
      Repository repo,
      CodeReviewRevWalk rw,
      CodeReviewCommit commit,
      ProjectState destProject,
      BranchNameKey destBranch,
      PatchSet.Id patchSetId,
      IdentifiedUser caller)
      throws MergeValidationException {
    String dest = destBranch.branch();
    if (dest.startsWith(BranchReviewConfig.STATE_NAMESPACE)) {
      throw new MergeValidationException(
          "refs under "
              + BranchReviewConfig.STATE_NAMESPACE
              + " are managed by the branch-review plugin");
    }
    if (RefNames.REFS_CONFIG.equals(dest)) {
      validateConfig(destProject, rw, commit);
      return;
    }
    if (settings.emergencyDisabled()) {
      return;
    }
    try {
      StateStore.Loaded<ChangeLink> link = store.loadLink(repo, patchSetId.changeId().get());
      if (!link.exists()) {
        return;
      }
      BranchReviewConfig cfg = configLoader.load(destProject.getNameKey());
      if (!cfg.isEnabled()) {
        // The plugin is off for this project: the Change behaves like any other Change.
        return;
      }
      Lifecycle lc = store.loadLifecycle(repo, link.value().sourceRef).value();
      if (lc == null || !link.value().lifecycleId.equals(lc.id)) {
        throw new MergeValidationException(
            "branch-review: the mapping for this Change is inconsistent; run branch-review status");
      }
      if (!dest.equals(lc.targetRef)) {
        throw new MergeValidationException(
            "branch-review: this Change targets "
                + dest
                + " but its branch review targets "
                + lc.targetRef
                + "; move it back, or abandon it and start a new lifecycle");
      }
      // A branch that is no longer routed anywhere keeps its review as an ordinary Change; one
      // routed to another target must not be submitted to the old one.
      Optional<String> configured = cfg.targetFor(lc.sourceRef);
      if (configured.isPresent() && !configured.get().equals(lc.targetRef)) {
        throw new MergeValidationException(
            "branch-review: the configured target of "
                + lc.sourceRef
                + " is now "
                + configured.get()
                + " but this review targets "
                + lc.targetRef
                + "; restore the previous target in branch-review.config, or abandon the Change"
                + " and start a new lifecycle");
      }
      checkSubmitType(repo, commit, dest);
      checkSynchronized(repo, rw, commit, lc);
    } catch (IOException e) {
      throw new MergeValidationException("branch-review: cannot read lifecycle state", e);
    }
  }

  /**
   * Whole-batch check run just before Submit updates the target refs. The per-change check above
   * cannot see other Changes submitted together (e.g. a topic). For every target ref update it
   * collects the newly introduced commits that are current patch sets of branch reviews being
   * submitted now (active lifecycle targeting that ref; patch sets of earlier, merged lifecycles
   * are ordinary history). Each of them must lie on the first-parent chain of the update: a review
   * reached only through a merge's second parent, directly or behind other commits, would be
   * integrated with an extra merge commit.
   */
  @Override
  public void preBranchUpdate(OnSubmitValidationListener.Arguments args)
      throws ValidationException {
    if (settings.emergencyDisabled() || !configLoader.load(args.getProject()).isEnabled()) {
      return;
    }
    try (Repository repo = repoManager.openRepository(args.getProject())) {
      RevWalk rw = args.getRevWalk();
      PatchSetIndex patchSets = new PatchSetIndex(repo);
      for (ReceiveCommand cmd : args.getCommands().values()) {
        if (!cmd.getRefName().startsWith("refs/heads/")
            || cmd.getNewId().equals(ObjectId.zeroId())) {
          continue;
        }
        ObjectId old = cmd.getOldId().equals(ObjectId.zeroId()) ? null : cmd.getOldId();
        Set<ObjectId> introduced = new HashSet<>();
        rw.reset();
        rw.markStart(rw.parseCommit(cmd.getNewId()));
        if (old != null) {
          rw.markUninteresting(rw.parseCommit(old));
        }
        for (RevCommit c : rw) {
          introduced.add(c.copy());
        }
        if (introduced.size() < 2) {
          continue; // A single new commit cannot hide a merge.
        }
        Set<ObjectId> firstParentChain = new HashSet<>();
        RevCommit c = rw.parseCommit(cmd.getNewId());
        while (introduced.contains(c)) {
          firstParentChain.add(c.copy());
          if (c.getParentCount() == 0) {
            break;
          }
          c = rw.parseCommit(c.getParent(0));
        }
        for (ObjectId id : introduced) {
          if (firstParentChain.contains(id)) {
            continue;
          }
          Optional<Integer> change = submittedReview(repo, patchSets, id, cmd.getRefName());
          if (change.isPresent()) {
            throw new ValidationException(
                "branch-review: Change "
                    + change.get()
                    + " would be merged into "
                    + cmd.getRefName()
                    + " through a merge commit. Submit it on its own (or rebase it), so that it"
                    + " adds exactly one commit.");
          }
        }
      }
    } catch (IOException e) {
      throw new ValidationException("branch-review: cannot inspect the submitted commits", e);
    }
  }

  /**
   * The number of the branch review being submitted now to {@code target} whose current patch set
   * is {@code commit}, if any.
   */
  private Optional<Integer> submittedReview(
      Repository repo, PatchSetIndex patchSets, ObjectId commit, String target) throws IOException {
    for (PatchSet.Id ps : patchSets.get(commit)) {
      StateStore.Loaded<ChangeLink> link = store.loadLink(repo, ps.changeId().get());
      if (!link.exists()) {
        continue;
      }
      Lifecycle lc = store.loadLifecycle(repo, link.value().sourceRef).value();
      if (lc != null
          && lc.isActive()
          && link.value().lifecycleId.equals(lc.id)
          && target.equals(lc.targetRef)) {
        return Optional.of(ps.changeId().get());
      }
    }
    return Optional.empty();
  }

  /**
   * Patch set refs by commit, built at most once per Submit and only when needed: one scan of
   * {@code refs/changes/} instead of a full ref scan per looked-up commit.
   */
  private static final class PatchSetIndex {
    private final Repository repo;
    private Map<ObjectId, List<PatchSet.Id>> byCommit;

    PatchSetIndex(Repository repo) {
      this.repo = repo;
    }

    List<PatchSet.Id> get(ObjectId commit) throws IOException {
      if (byCommit == null) {
        byCommit = new HashMap<>();
        for (Ref r : repo.getRefDatabase().getRefsByPrefix(RefNames.REFS_CHANGES)) {
          PatchSet.Id ps = PatchSet.Id.fromRef(r.getName());
          if (ps != null && r.getObjectId() != null) {
            byCommit.computeIfAbsent(r.getObjectId().copy(), k -> new ArrayList<>()).add(ps);
          }
        }
      }
      return byCommit.getOrDefault(commit, List.of());
    }
  }

  private void checkSubmitType(Repository repo, CodeReviewCommit commit, String dest)
      throws MergeValidationException, IOException {
    SubmitType type = changeDataFactory.create(commit.notes()).submitTypeRecord().type;
    if (type == null || ONE_COMMIT_TYPES.contains(type)) {
      return;
    }
    if (type == SubmitType.MERGE_IF_NECESSARY) {
      Ref target = repo.exactRef(dest);
      if (target != null
          && commit.getParentCount() == 1
          && commit.getParent(0).equals(target.getObjectId())) {
        return; // Will fast-forward: exactly one new commit.
      }
      throw new MergeValidationException(
          "branch-review: "
              + dest
              + " moved since this patch set was created and the project submit type"
              + " MERGE_IF_NECESSARY would add a merge commit. Rebase the Change (or push the"
              + " branch again) and submit the new patch set.");
    }
    throw new MergeValidationException(
        "branch-review: submit type "
            + type
            + " would add more than one commit; use FAST_FORWARD_ONLY, REBASE_IF_NECESSARY,"
            + " REBASE_ALWAYS, CHERRY_PICK or MERGE_IF_NECESSARY");
  }

  private void checkSynchronized(Repository repo, RevWalk rw, CodeReviewCommit commit, Lifecycle lc)
      throws MergeValidationException, IOException {
    switch (lc.syncState) {
      case SYNCED, SOURCE_DELETED -> {}
      default ->
          throw new MergeValidationException(
              "branch-review: the source branch "
                  + lc.sourceRef
                  + " is not synchronized ("
                  + lc.syncState
                  + (lc.lastError == null ? "" : ": " + lc.lastError)
                  + "); the current patch set does not represent it");
    }
    if (lc.syncState == Lifecycle.SyncState.SOURCE_DELETED && repo.exactRef(lc.sourceRef) != null) {
      // The branch was recreated: the retained review no longer belongs to the live branch.
      throw new MergeValidationException(
          "branch-review: "
              + lc.sourceRef
              + " was deleted and recreated; this Change belongs to the previous branch. Abandon"
              + " it, or continue it explicitly with branch-review sync --resume");
    }
    if (lc.syncState == Lifecycle.SyncState.SYNCED) {
      Ref src = repo.exactRef(lc.sourceRef);
      if (src == null || !src.getObjectId().name().equals(lc.lastSourceId)) {
        throw new MergeValidationException(
            "branch-review: "
                + lc.sourceRef
                + " has advanced since the current patch set was generated; wait for the"
                + " synchronization (or run branch-review sync) and review the new patch set");
      }
    }
    ObjectId synthetic = ObjectId.fromString(lc.lastSyntheticCommit);
    if (!commit.equals(synthetic)) {
      try (ObjectInserter ins = repo.newObjectInserter();
          RevWalk walk = new RevWalk(ins.newReader())) {
        RevCommit c = walk.parseCommit(commit);
        if (!SyncService.isDerivative(repo, ins, walk, c, walk.parseCommit(synthetic))) {
          throw new MergeValidationException(
              "branch-review: this patch set was not generated from "
                  + lc.sourceRef
                  + "; synchronize or resolve the divergence first");
        }
      }
    }
  }

  private void validateConfig(ProjectState project, RevWalk rw, CodeReviewCommit commit)
      throws MergeValidationException {
    try {
      List<String> errors =
          configValidator.validate(project.getNameKey(), rw.getObjectReader(), commit);
      if (!errors.isEmpty()) {
        throw new MergeValidationException(
            "invalid " + ConfigParser.FILE_NAME + ": " + String.join("; ", errors));
      }
    } catch (CommitValidationException e) {
      throw new MergeValidationException(e.getMessage(), e);
    }
  }
}
