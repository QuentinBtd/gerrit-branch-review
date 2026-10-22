// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.squash;

import com.google.common.collect.ImmutableList;
import io.github.quentinbtd.gerrit.branchreview.text.MessageGenerator.SourceCommit;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;

/**
 * Computes the tree of a synthetic squash commit.
 *
 * <p>Algorithm: given a captured source tip {@code S} and target tip {@code T}, the result tree is
 * the in-core recursive three-way merge of {@code S} into {@code T} using their merge base(s). The
 * synthetic commit has {@code T} as its only parent. This applies exactly the net contribution of
 * the branch ({@code base..S}) on top of the current target, so unrelated target changes made after
 * the branch point are preserved, and the source's own commits never become target ancestors.
 *
 * <p>Conflicts, unrelated histories and over-limit contributions are reported as outcomes; no tree
 * containing conflict markers is ever returned.
 */
public final class SquashPlanner {
  public record Limits(int maxCommits) {}

  public enum Outcome {
    /** A non-empty contribution with a clean merged tree. */
    OK,
    /** The source adds nothing relative to the target. */
    EMPTY,
    /** Source and target share no history. */
    UNRELATED,
    /** The three-way merge has conflicts. */
    CONFLICT,
    /** More contribution commits than {@link Limits#maxCommits()}. */
    TOO_LARGE
  }

  public record Plan(
      Outcome outcome,
      ObjectId sourceId,
      ObjectId targetId,
      ImmutableList<ObjectId> mergeBases,
      ObjectId treeId,
      ImmutableList<SourceCommit> commits,
      ImmutableList<String> conflictPaths) {
    public boolean ok() {
      return outcome == Outcome.OK;
    }
  }

  private SquashPlanner() {}

  /**
   * Plans the squash. New tree objects are written through {@code inserter}; the caller flushes it
   * if the plan is used.
   */
  public static Plan plan(
      Repository repo, ObjectInserter inserter, ObjectId source, ObjectId target, Limits limits)
      throws IOException {
    try (RevWalk rw = new RevWalk(inserter.newReader())) {
      RevCommit s = rw.parseCommit(source);
      RevCommit t = rw.parseCommit(target);

      // Bounded counting pass first: TOPO/REVERSE sorting buffers the whole walk in memory.
      rw.reset();
      rw.markStart(s);
      rw.markUninteresting(t);
      int count = 0;
      while (rw.next() != null) {
        if (++count > limits.maxCommits()) {
          return result(Outcome.TOO_LARGE, s, t, List.of(), null, List.of(), List.of());
        }
      }

      // Contribution commits: reachable from S but not from T, oldest first.
      rw.reset();
      rw.sort(RevSort.TOPO);
      rw.sort(RevSort.REVERSE, true);
      rw.markStart(s);
      rw.markUninteresting(t);
      List<SourceCommit> commits = new ArrayList<>(count);
      for (RevCommit c : rw) {
        commits.add(
            new SourceCommit(c.abbreviate(12).name(), c.getFullMessage(), c.getParentCount() > 1));
      }
      if (commits.isEmpty()) {
        return result(Outcome.EMPTY, s, t, List.of(), t.getTree(), List.of(), List.of());
      }

      List<ObjectId> bases = mergeBases(rw, s, t);
      if (bases.isEmpty()) {
        return result(Outcome.UNRELATED, s, t, bases, null, commits, List.of());
      }

      ResolveMerger merger =
          (ResolveMerger) MergeStrategy.RECURSIVE.newMerger(inserter, repo.getConfig());
      boolean clean = merger.merge(t, s);
      if (!clean) {
        List<String> paths = new ArrayList<>(merger.getUnmergedPaths());
        if (merger.getFailingPaths() != null) {
          paths.addAll(merger.getFailingPaths().keySet());
        }
        return result(Outcome.CONFLICT, s, t, bases, null, commits, paths);
      }
      ObjectId tree = merger.getResultTreeId();
      if (tree.equals(t.getTree())) {
        return result(Outcome.EMPTY, s, t, bases, tree, commits, List.of());
      }
      return result(Outcome.OK, s, t, bases, tree, commits, List.of());
    }
  }

  private static List<ObjectId> mergeBases(RevWalk rw, RevCommit a, RevCommit b)
      throws IOException {
    rw.reset();
    rw.setRevFilter(RevFilter.MERGE_BASE);
    rw.markStart(rw.parseCommit(a));
    rw.markStart(rw.parseCommit(b));
    List<ObjectId> out = new ArrayList<>();
    for (RevCommit c : rw) {
      out.add(c.copy());
    }
    return out;
  }

  private static Plan result(
      Outcome o,
      RevCommit s,
      RevCommit t,
      List<ObjectId> bases,
      ObjectId tree,
      List<SourceCommit> commits,
      List<String> conflicts) {
    return new Plan(
        o,
        s.copy(),
        t.copy(),
        ImmutableList.copyOf(bases),
        tree == null ? null : tree.copy(),
        ImmutableList.copyOf(commits),
        ImmutableList.copyOf(conflicts.stream().sorted().distinct().limit(50).toList()));
  }
}
