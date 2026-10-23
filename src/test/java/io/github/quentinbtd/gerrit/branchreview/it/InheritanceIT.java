// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.common.ChangeInfo;
import java.util.List;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.Before;
import org.junit.Test;

/** The test project is a child of a dedicated parent project that has no branch to review. */
public class InheritanceIT extends AbstractBranchReviewIT {
  private static final String FEATURE = "refs/heads/feature/x";
  private static final String BUGFIX = "refs/heads/bugfix/x";

  private Project.NameKey parent;

  @Before
  public void reparent() throws Exception {
    parent = projectOperations.newProject().createEmptyCommit(false).create();
    gApi.projects().name(project.get()).parent(parent.get());
    projectCache.evictAndReindex(project);
  }

  @Test
  public void parentConfigEnablesChild() throws Exception {
    setConfig(parent, DEFAULT_CONFIG);
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    List<ChangeInfo> cs = changes();
    assertThat(cs).hasSize(1);
    assertThat(cs.get(0).branch).isEqualTo("master");
    assertThat(cs.get(0).workInProgress).isTrue();
  }

  @Test
  public void childOptsOut() throws Exception {
    setConfig(parent, DEFAULT_CONFIG);
    setConfig("[branchReview]\n  enabled = false\n");
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(pluginLines(r)).isEmpty();
    assertThat(changes()).isEmpty();
  }

  @Test
  public void childKeysReplaceParentKeys() throws Exception {
    setConfig(parent, DEFAULT_CONFIG);
    setConfig(
        "[branchReview]\n"
            + "  sourceBranchPattern = refs/heads/bugfix/*\n"
            + "  workInProgress = false\n");
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    // The child's list replaces the parent's: feature/* is no longer reviewed.
    assertOk(push(repo, FEATURE), FEATURE);
    assertThat(changes()).isEmpty();

    assertOk(push(repo, BUGFIX), BUGFIX);
    List<ChangeInfo> cs = changes();
    assertThat(cs).hasSize(1);
    assertThat(cs.get(0).branch).isEqualTo("master");
    assertThat(cs.get(0).workInProgress).isNull();
  }

  @Test
  public void parentUpdateReachesChild() throws Exception {
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    assertOk(push(repo, FEATURE), FEATURE);
    assertThat(changes()).isEmpty();

    setConfig(parent, DEFAULT_CONFIG);
    assertOk(push(repo, BUGFIX), BUGFIX);
    assertThat(changes()).hasSize(1);

    setConfig(parent, DEFAULT_CONFIG.replace("enabled = true", "enabled = false"));
    commits(repo, "b", 1);
    PushResult r = push(repo, "refs/heads/bugfix/y");
    assertOk(r, "refs/heads/bugfix/y");
    assertThat(pluginLines(r)).isEmpty();
    assertThat(changes()).hasSize(1);
  }

  @Test
  public void childConfigIsValidatedWithItsParent() throws Exception {
    setConfig(
        parent,
        "[branchReview]\n"
            + "  enabled = true\n"
            + "[target \"refs/heads/master\"]\n"
            + "  sourceBranchPattern = refs/heads/feature/*\n");
    PushResult r =
        pushConfig(
            "[target \"refs/heads/stable\"]\n  sourceBranchPattern = refs/heads/feature/*\n");
    assertThat(r.getRemoteUpdate("refs/meta/config").getStatus())
        .isNotEqualTo(RemoteRefUpdate.Status.OK);
    assertThat(r.getMessages()).contains("is routed to both");

    // An override of an inherited route is complete with the parent's patterns.
    r = pushConfig("[target \"refs/heads/master\"]\n  workInProgress = false\n");
    assertOk(r, "refs/meta/config");
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    commits(repo, "a", 1);
    assertOk(push(repo, FEATURE), FEATURE);
    assertThat(changes()).hasSize(1);
    assertThat(changes().get(0).workInProgress).isNull();
  }

  @Test
  public void removingAnOverrideCannotExposeAnInvalidInheritedConfig() throws Exception {
    // Written as the server: the parent's routes conflict, and only the child's override of the
    // stable route makes the child's effective configuration valid.
    setConfig(
        parent,
        "[branchReview]\n"
            + "  enabled = true\n"
            + "[target \"refs/heads/master\"]\n"
            + "  sourceBranchPattern = refs/heads/feature/*\n"
            + "[target \"refs/heads/stable\"]\n"
            + "  sourceBranchPattern = refs/heads/feature/*\n");
    setConfig("[target \"refs/heads/stable\"]\n  sourceBranchPattern = refs/heads/bugfix/*\n");

    for (String text : new String[] {null, "# nothing\n"}) {
      PushResult r = pushConfig(text);
      assertThat(r.getRemoteUpdate("refs/meta/config").getStatus())
          .isNotEqualTo(RemoteRefUpdate.Status.OK);
      assertThat(r.getMessages()).contains("is routed to both");
    }
  }

  @Test
  public void parentWithoutBranchesOnlyWarnsAboutTargets() throws Exception {
    TestRepository<InMemoryRepository> repo = cloneProject(parent, admin);
    repo.git().fetch().setRemote("origin").setRefSpecs("refs/meta/config:refs/meta/config").call();
    repo.reset("refs/meta/config");
    repo.branch("HEAD")
        .commit()
        .author(admin.newIdent())
        .committer(admin.newIdent())
        .message("enable branch-review for the children")
        .add("branch-review.config", DEFAULT_CONFIG)
        .create();
    PushResult r = push(repo, "HEAD:refs/meta/config", false);
    assertOk(r, "refs/meta/config");
    assertThat(r.getMessages()).contains("target branch refs/heads/master does not exist");
  }

  /** Pushes {@code text} as branch-review.config of the project; {@code null} removes the file. */
  private PushResult pushConfig(String text) throws Exception {
    TestRepository<InMemoryRepository> repo = cloneAs(admin);
    repo.git().fetch().setRemote("origin").setRefSpecs("refs/meta/config:refs/meta/config").call();
    repo.reset("refs/meta/config");
    var b =
        repo.branch("HEAD")
            .commit()
            .author(admin.newIdent())
            .committer(admin.newIdent())
            .message("update branch-review.config");
    if (text == null) {
      b.rm("branch-review.config");
    } else {
      b.add("branch-review.config", text);
    }
    b.create();
    return push(repo, "HEAD:refs/meta/config", false);
  }
}
