// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.inject.Inject;
import io.github.quentinbtd.gerrit.branchreview.api.PostSync;
import io.github.quentinbtd.gerrit.branchreview.state.Lifecycle;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.Before;

/** Shared fixtures for in-process acceptance tests of the plugin. */
@TestPlugin(name = "branch-review", sysModule = "io.github.quentinbtd.gerrit.branchreview.Module")
public abstract class AbstractBranchReviewIT extends LightweightPluginDaemonTest {
  protected static final String DEFAULT_CONFIG =
      """
      [branchReview]
        enabled = true
        targetBranch = refs/heads/master
        sourceBranchPattern = refs/heads/feature/*
        sourceBranchPattern = refs/heads/bugfix/*
        excludeBranchPattern = refs/heads/feature/private/*
      """;

  @Inject protected ProjectOperations projectOperations;
  @Inject protected StateStore stateStore;

  @Before
  public void allowFeaturePushes() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allow(Permission.CREATE)
                .ref("refs/heads/feature/*")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .add(
            allow(Permission.PUSH)
                .ref("refs/heads/feature/*")
                .group(SystemGroupBackend.REGISTERED_USERS)
                .force(true))
        .add(
            allow(Permission.CREATE)
                .ref("refs/heads/bugfix/*")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .add(
            allow(Permission.PUSH)
                .ref("refs/heads/bugfix/*")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();
  }

  /** Writes branch-review.config at the root of refs/meta/config (as the server). */
  protected void setConfig(String text) throws Exception {
    setConfig(project, text);
  }

  /** Writes branch-review.config at the root of refs/meta/config of {@code p} (as the server). */
  protected void setConfig(Project.NameKey p, String text) throws Exception {
    try (Repository repo = repoManager.openRepository(p);
        TestRepository<Repository> tr = new TestRepository<>(repo)) {
      var b = tr.branch("refs/meta/config").commit().message("update branch-review.config");
      if (text == null) {
        b.rm("branch-review.config");
      } else {
        b.add("branch-review.config", text);
      }
      b.create();
    }
    projectCache.evictAndReindex(p);
  }

  protected void enable() throws Exception {
    setConfig(DEFAULT_CONFIG);
  }

  private final java.util.Map<TestRepository<?>, TestAccount> owners =
      new java.util.IdentityHashMap<>();

  protected TestRepository<InMemoryRepository> cloneAs(TestAccount account) throws Exception {
    TestRepository<InMemoryRepository> r = cloneProject(project, account);
    owners.put(r, account);
    return r;
  }

  /** Creates {@code n} commits on top of the current HEAD of {@code repo}. */
  protected RevCommit commits(TestRepository<InMemoryRepository> repo, String prefix, int n)
      throws Exception {
    RevCommit c = null;
    for (int i = 1; i <= n; i++) {
      c =
          repo.branch("HEAD")
              .commit()
              .insertChangeId()
              .message(prefix + " commit " + i)
              .author(owners.get(repo).newIdent())
              .committer(owners.get(repo).newIdent())
              .add(prefix + "/file" + i + ".txt", prefix + " content " + i + "\n")
              .create();
    }
    return c;
  }

  protected PushResult push(TestRepository<InMemoryRepository> repo, String ref) throws Exception {
    return push(repo, "HEAD:" + ref, false);
  }

  protected PushResult push(TestRepository<InMemoryRepository> repo, String spec, boolean force)
      throws Exception {
    return repo.git()
        .push()
        .setRemote("origin")
        .setRefSpecs(new RefSpec((force ? "+" : "") + spec))
        .call()
        .iterator()
        .next();
  }

  protected static void assertOk(PushResult r, String ref) {
    RemoteRefUpdate u = r.getRemoteUpdate(ref);
    assertThat(u).isNotNull();
    assertWithMessage(u.getMessage() + "\n" + r.getMessages())
        .that(u.getStatus())
        .isEqualTo(RemoteRefUpdate.Status.OK);
  }

  protected static List<String> pluginLines(PushResult r) {
    List<String> out = new ArrayList<>();
    for (String l : r.getMessages().split("\n")) {
      if (l.startsWith("branch-review: ")) {
        out.add(l.substring("branch-review: ".length()));
      }
    }
    return out;
  }

  protected Lifecycle lifecycle(String sourceRef) throws Exception {
    try (Repository repo = repoManager.openRepository(project)) {
      return stateStore.loadLifecycle(repo, sourceRef).value();
    }
  }

  protected ObjectId refValue(String ref) throws Exception {
    try (Repository repo = repoManager.openRepository(project)) {
      Ref r = repo.exactRef(ref);
      return r == null ? null : r.getObjectId();
    }
  }

  protected List<ChangeInfo> changes() throws Exception {
    return gApi.changes()
        .query("project:" + project.get())
        .withOptions(ListChangesOption.CURRENT_REVISION, ListChangesOption.CURRENT_COMMIT)
        .get();
  }

  protected int onlyChange() throws Exception {
    List<ChangeInfo> cs = changes();
    assertThat(cs).hasSize(1);
    return cs.get(0)._number;
  }

  protected void setSubmitType(com.google.gerrit.extensions.client.SubmitType type)
      throws Exception {
    com.google.gerrit.extensions.api.projects.ConfigInput in =
        new com.google.gerrit.extensions.api.projects.ConfigInput();
    in.submitType = type;
    gApi.projects().name(project.get()).config(in);
  }

  /** Marks ready, approves and submits as admin. */
  protected void approveAndSubmit(int number) throws Exception {
    var api = gApi.changes().id(project.get(), number);
    if (Boolean.TRUE.equals(api.get().workInProgress)) {
      api.setReadyForReview();
    }
    api.current().review(com.google.gerrit.extensions.api.changes.ReviewInput.approve());
    api.current().submit();
  }

  /** Commits on the server-side branch, bypassing receive (simulates other target work). */
  protected RevCommit serverCommit(String ref, String path, String content) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> tr = new TestRepository<>(repo)) {
      return tr.branch(ref).commit().message("server " + path).add(path, content).create();
    }
  }

  /** Commits reachable from {@code tip} but not from {@code base}. */
  protected List<RevCommit> between(ObjectId base, ObjectId tip) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
      rw.markStart(rw.parseCommit(tip));
      if (base != null) {
        rw.markUninteresting(rw.parseCommit(base));
      }
      List<RevCommit> out = new ArrayList<>();
      for (RevCommit c : rw) {
        out.add(c);
      }
      return out;
    }
  }

  protected boolean isAncestor(ObjectId ancestor, ObjectId tip) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
      return rw.isMergedInto(rw.parseCommit(ancestor), rw.parseCommit(tip));
    }
  }

  protected String fileAt(ObjectId commit, String path) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(repo);
        org.eclipse.jgit.treewalk.TreeWalk tw =
            org.eclipse.jgit.treewalk.TreeWalk.forPath(
                repo, path, rw.parseCommit(commit).getTree())) {
      return tw == null
          ? null
          : new String(
              repo.open(tw.getObjectId(0)).getBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  protected static void waitFor(java.util.concurrent.Callable<Boolean> cond) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (!cond.call()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within 30s");
      }
      Thread.sleep(50);
    }
  }

  protected static String joined(PushResult r) {
    return String.join("\n", pluginLines(r));
  }

  /** POSTs to the sync endpoint as {@code who} and returns the JSON body. */
  protected String syncViaRest(TestAccount who, String ref, PostSync.Input in) throws Exception {
    in.ref = ref;
    var resp = restSession(who).post("/projects/" + project.get() + "/branch-review~sync", in);
    String content = resp.getEntityContent();
    assertWithMessage(content).that(resp.getStatusCode()).isEqualTo(200);
    return content;
  }

  protected com.google.gerrit.acceptance.RestResponse status(TestAccount who, String ref)
      throws Exception {
    return restSession(who)
        .get(
            "/projects/"
                + project.get()
                + "/branch-review~status?ref="
                + java.net.URLEncoder.encode(ref, java.nio.charset.StandardCharsets.UTF_8));
  }

  protected com.google.gerrit.acceptance.RestSession restSession(TestAccount who) {
    if (who.equals(admin)) {
      return adminRestSession;
    }
    if (who.equals(user)) {
      return userRestSession;
    }
    return server.createRestSession(who);
  }

  protected ChangeInfo change(int number) throws Exception {
    return gApi.changes()
        .id(project.get(), number)
        .get(
            ListChangesOption.CURRENT_REVISION,
            ListChangesOption.CURRENT_COMMIT,
            ListChangesOption.ALL_REVISIONS);
  }
}
