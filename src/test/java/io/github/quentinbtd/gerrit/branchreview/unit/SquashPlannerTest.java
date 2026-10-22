// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.unit;

import static com.google.common.truth.Truth.assertThat;

import io.github.quentinbtd.gerrit.branchreview.squash.SquashPlanner;
import io.github.quentinbtd.gerrit.branchreview.squash.SquashPlanner.Outcome;
import io.github.quentinbtd.gerrit.branchreview.squash.SquashPlanner.Plan;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.Before;
import org.junit.Test;

public class SquashPlannerTest {
  private InMemoryRepository repo;
  private TestRepository<InMemoryRepository> tr;

  @Before
  public void setUp() throws Exception {
    repo = new InMemoryRepository(new DfsRepositoryDescription("t"));
    tr = new TestRepository<>(repo);
  }

  private Plan plan(RevCommit source, RevCommit target, int max) throws Exception {
    try (ObjectInserter ins = repo.newObjectInserter()) {
      Plan p = SquashPlanner.plan(repo, ins, source, target, new SquashPlanner.Limits(max));
      ins.flush();
      return p;
    }
  }

  private String read(ObjectId tree, String path) throws Exception {
    try (TreeWalk tw = TreeWalk.forPath(repo, path, tr.getRevWalk().parseTree(tree))) {
      return tw == null
          ? null
          : new String(
              repo.open(tw.getObjectId(0)).getBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  public void appliesNetContributionOntoAdvancedTarget() throws Exception {
    RevCommit base = tr.commit().add("a.txt", "a\n").add("b.txt", "b\n").create();
    RevCommit s1 = tr.commit().parent(base).add("a.txt", "a2\n").add("new.txt", "n\n").create();
    RevCommit s2 = tr.commit().parent(s1).rm("b.txt").create();
    RevCommit target = tr.commit().parent(base).add("t.txt", "target\n").create();
    Plan p = plan(s2, target, 100);
    assertThat(p.outcome()).isEqualTo(Outcome.OK);
    assertThat(p.commits()).hasSize(2);
    assertThat(p.mergeBases()).containsExactly(base);
    assertThat(read(p.treeId(), "a.txt")).isEqualTo("a2\n");
    assertThat(read(p.treeId(), "new.txt")).isEqualTo("n\n");
    assertThat(read(p.treeId(), "b.txt")).isNull();
    // Unrelated target work is preserved rather than replaced by the source tree.
    assertThat(read(p.treeId(), "t.txt")).isEqualTo("target\n");
  }

  @Test
  public void targetMergedIntoSourceIsNotCountedAsContribution() throws Exception {
    RevCommit base = tr.commit().add("a.txt", "a\n").create();
    RevCommit t1 = tr.commit().parent(base).add("t.txt", "t\n").create();
    RevCommit s1 = tr.commit().parent(base).add("s.txt", "s\n").create();
    RevCommit merge =
        tr.commit().parent(s1).parent(t1).add("s.txt", "s\n").add("t.txt", "t\n").create();
    Plan p = plan(merge, t1, 100);
    assertThat(p.outcome()).isEqualTo(Outcome.OK);
    assertThat(p.mergeBases()).containsExactly(t1);
    assertThat(read(p.treeId(), "s.txt")).isEqualTo("s\n");
    assertThat(p.commits().stream().filter(c -> c.merge()).count()).isEqualTo(1);
  }

  @Test
  public void emptyWhenSourceIsAncestorOrAddsNothing() throws Exception {
    RevCommit base = tr.commit().add("a.txt", "a\n").create();
    RevCommit target = tr.commit().parent(base).add("t.txt", "t\n").create();
    assertThat(plan(base, target, 10).outcome()).isEqualTo(Outcome.EMPTY);
    RevCommit revert1 = tr.commit().parent(base).add("a.txt", "x\n").create();
    RevCommit revert2 = tr.commit().parent(revert1).add("a.txt", "a\n").create();
    assertThat(plan(revert2, target, 10).outcome()).isEqualTo(Outcome.EMPTY);
  }

  @Test
  public void conflictsAreReportedWithoutATree() throws Exception {
    RevCommit base = tr.commit().add("a.txt", "a\n").create();
    RevCommit source = tr.commit().parent(base).add("a.txt", "source\n").create();
    RevCommit target = tr.commit().parent(base).add("a.txt", "target\n").create();
    Plan p = plan(source, target, 10);
    assertThat(p.outcome()).isEqualTo(Outcome.CONFLICT);
    assertThat(p.treeId()).isNull();
    assertThat(p.conflictPaths()).containsExactly("a.txt");
  }

  @Test
  public void unrelatedHistoriesAreRejected() throws Exception {
    RevCommit a = tr.commit().add("a.txt", "a\n").create();
    RevCommit b = tr.commit().add("b.txt", "b\n").create();
    assertThat(plan(a, b, 10).outcome()).isEqualTo(Outcome.UNRELATED);
  }

  @Test
  public void limitIsEnforced() throws Exception {
    RevCommit base = tr.commit().add("a.txt", "a\n").create();
    RevCommit c = base;
    for (int i = 0; i < 5; i++) {
      c = tr.commit().parent(c).add("f" + i, "x").create();
    }
    assertThat(plan(c, base, 4).outcome()).isEqualTo(Outcome.TOO_LARGE);
    assertThat(plan(c, base, 5).outcome()).isEqualTo(Outcome.OK);
  }

  @Test
  public void multipleMergeBasesAreHandledByRecursiveMerge() throws Exception {
    RevCommit base = tr.commit().add("a.txt", "a\n").create();
    RevCommit x = tr.commit().parent(base).add("x.txt", "x\n").create();
    RevCommit y = tr.commit().parent(base).add("y.txt", "y\n").create();
    RevCommit s =
        tr.commit()
            .parent(x)
            .parent(y)
            .add("x.txt", "x\n")
            .add("y.txt", "y\n")
            .add("s.txt", "s\n")
            .create();
    RevCommit t =
        tr.commit()
            .parent(y)
            .parent(x)
            .add("x.txt", "x\n")
            .add("y.txt", "y\n")
            .add("t.txt", "t\n")
            .create();
    Plan p = plan(s, t, 10);
    assertThat(p.mergeBases()).hasSize(2);
    assertThat(p.outcome()).isEqualTo(Outcome.OK);
    assertThat(read(p.treeId(), "s.txt")).isEqualTo("s\n");
    assertThat(read(p.treeId(), "t.txt")).isEqualTo("t\n");
  }

  @Test
  public void preservesModesSymlinksBinaryAndGitlinks() throws Exception {
    RevCommit base = tr.commit().add("a.txt", "a\n").create();
    byte[] binary = new byte[] {0, 1, 2, (byte) 0xff, 0, 10};
    ObjectId blob = tr.blob(binary);
    ObjectId link = tr.blob("a.txt");
    ObjectId sub = ObjectId.fromString("0123456789012345678901234567890123456789");
    RevCommit source =
        tr.commit()
            .parent(base)
            .edit(
                new org.eclipse.jgit.dircache.DirCacheEditor.PathEdit("bin.dat") {
                  @Override
                  public void apply(org.eclipse.jgit.dircache.DirCacheEntry e) {
                    e.setFileMode(FileMode.REGULAR_FILE);
                    e.setObjectId(blob);
                  }
                })
            .edit(
                new org.eclipse.jgit.dircache.DirCacheEditor.PathEdit("run.sh") {
                  @Override
                  public void apply(org.eclipse.jgit.dircache.DirCacheEntry e) {
                    e.setFileMode(FileMode.EXECUTABLE_FILE);
                    e.setObjectId(blob);
                  }
                })
            .edit(
                new org.eclipse.jgit.dircache.DirCacheEditor.PathEdit("link") {
                  @Override
                  public void apply(org.eclipse.jgit.dircache.DirCacheEntry e) {
                    e.setFileMode(FileMode.SYMLINK);
                    e.setObjectId(link);
                  }
                })
            .edit(
                new org.eclipse.jgit.dircache.DirCacheEditor.PathEdit("sub") {
                  @Override
                  public void apply(org.eclipse.jgit.dircache.DirCacheEntry e) {
                    e.setFileMode(FileMode.GITLINK);
                    e.setObjectId(sub);
                  }
                })
            .create();
    RevCommit target = tr.commit().parent(base).add("t.txt", "t\n").create();
    Plan p = plan(source, target, 10);
    assertThat(p.outcome()).isEqualTo(Outcome.OK);
    RevTree tree = tr.getRevWalk().parseTree(p.treeId());
    assertThat(mode(tree, "bin.dat")).isEqualTo(FileMode.REGULAR_FILE);
    assertThat(mode(tree, "run.sh")).isEqualTo(FileMode.EXECUTABLE_FILE);
    assertThat(mode(tree, "link")).isEqualTo(FileMode.SYMLINK);
    assertThat(mode(tree, "sub")).isEqualTo(FileMode.GITLINK);
    try (TreeWalk tw = TreeWalk.forPath(repo, "bin.dat", tree)) {
      assertThat(repo.open(tw.getObjectId(0)).getBytes()).isEqualTo(binary);
    }
    try (TreeWalk tw = TreeWalk.forPath(repo, "sub", tree)) {
      assertThat(tw.getObjectId(0)).isEqualTo(sub);
    }
  }

  private FileMode mode(RevTree tree, String path) throws Exception {
    try (TreeWalk tw = TreeWalk.forPath(repo, path, tree)) {
      return tw.getFileMode(0);
    }
  }
}
