// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;

import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.server.group.SystemGroupBackend;
import io.github.quentinbtd.gerrit.branchreview.api.PostSync;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.Test;

public class PermissionsIT extends AbstractBranchReviewIT {
  private static final String FEATURE = "refs/heads/feature/perm";

  @Test
  public void sourcePushAllowedButReviewCreationDenied() throws Exception {
    enable();
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            block(Permission.PUSH)
                .ref("refs/for/refs/heads/master")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit tip = commits(repo, "a", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(refValue(FEATURE)).isEqualTo(tip);
    assertThat(joined(r)).contains("not allowed to create a review for refs/heads/master");
    assertThat(changes()).isEmpty();
  }

  @Test
  public void otherUserCannotAddPatchSetWithoutPermission() throws Exception {
    enable();
    TestRepository<InMemoryRepository> owner = cloneAs(user);
    commits(owner, "a", 1);
    push(owner, FEATURE);
    int n = onlyChange();
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            block(Permission.ADD_PATCH_SET)
                .ref("refs/for/refs/heads/master")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    TestAccount other = accountCreator.create("other", "other@example.com", "Other", null);
    TestRepository<InMemoryRepository> repo = cloneAs(other);
    repo.git().fetch().setRemote("origin").setRefSpecs("+" + FEATURE + ":" + FEATURE).call();
    repo.reset(refValue(FEATURE));
    commits(repo, "b", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("not allowed to upload a patch set");
    assertThat(change(n).revisions).hasSize(1);
    assertThat(change(n).owner._accountId).isEqualTo(user.id().get());
  }

  @Test
  public void anotherAuthorizedPusherUploadsWithoutTakingOwnership() throws Exception {
    enable();
    TestRepository<InMemoryRepository> owner = cloneAs(user);
    commits(owner, "a", 1);
    push(owner, FEATURE);
    int n = onlyChange();
    TestAccount other = accountCreator.create("other2", "other2@example.com", "Other2", null);
    TestRepository<InMemoryRepository> repo = cloneAs(other);
    repo.git().fetch().setRemote("origin").setRefSpecs("+" + FEATURE + ":" + FEATURE).call();
    repo.reset(refValue(FEATURE));
    commits(repo, "b", 1);
    push(repo, FEATURE);
    var c = change(n);
    assertThat(c.revisions).hasSize(2);
    assertThat(c.owner._accountId).isEqualTo(user.id().get());
    assertThat(c.revisions.get(c.currentRevision).uploader._accountId).isEqualTo(other.id().get());
  }

  @Test
  public void forcePushStillNeedsForcePermission() throws Exception {
    enable();
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            block(Permission.PUSH)
                .ref("refs/heads/bugfix/*")
                .group(SystemGroupBackend.REGISTERED_USERS)
                .force(true))
        .update();
    TestRepository<InMemoryRepository> repo = cloneAs(user);
    RevCommit base = repo.getRevWalk().parseCommit(refValue("refs/heads/master"));
    commits(repo, "a", 1);
    push(repo, "refs/heads/bugfix/1");
    int n = onlyChange();
    repo.reset(base);
    commits(repo, "rewritten", 1);
    PushResult r = push(repo, "HEAD:refs/heads/bugfix/1", true);
    assertThat(r.getRemoteUpdate("refs/heads/bugfix/1").getStatus())
        .isNotEqualTo(RemoteRefUpdate.Status.OK);
    assertThat(pluginLines(r)).isEmpty();
    assertThat(change(n).revisions).hasSize(1);
  }

  @Test
  public void stateRefsCannotBeWrittenByUsers() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(admin);
    commits(repo, "a", 1);
    push(repo, FEATURE);
    String stateRef = StateStore.lifecycleRef(FEATURE);
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allow(Permission.PUSH)
                .ref("refs/meta/*")
                .group(SystemGroupBackend.REGISTERED_USERS)
                .force(true))
        .add(allow(Permission.CREATE).ref("refs/meta/*").group(SystemGroupBackend.REGISTERED_USERS))
        .add(allow(Permission.DELETE).ref("refs/meta/*").group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    var before = refValue(stateRef);
    for (String spec :
        new String[] {
          "HEAD:" + stateRef,
          "HEAD:refs/meta/branch-review/forged",
          "HEAD:refs/for/refs/meta/branch-review/forged",
          ":" + stateRef
        }) {
      PushResult r = push(repo, spec, true);
      for (RemoteRefUpdate u : r.getRemoteUpdates()) {
        assertThat(u.getStatus()).isNotEqualTo(RemoteRefUpdate.Status.OK);
      }
    }
    assertThat(refValue(stateRef)).isEqualTo(before);
    assertThat(refValue("refs/meta/branch-review/forged")).isNull();
  }

  @Test
  public void invalidConfigUpdateIsRejected() throws Exception {
    TestRepository<InMemoryRepository> repo = cloneAs(admin);
    repo.git().fetch().setRemote("origin").setRefSpecs("refs/meta/config:refs/meta/config").call();
    repo.reset("refs/meta/config");
    repo.branch("HEAD")
        .commit()
        .author(admin.newIdent())
        .committer(admin.newIdent())
        .message("bad config")
        .add(
            "branch-review.config",
            "[branchReview]\n"
                + "  enabled = true\n"
                + "  targetBranch = refs/heads/master\n"
                + "  sourceBranchPattern = ^refs/heads/.*\n")
        .create();
    PushResult r = push(repo, "HEAD:refs/meta/config", false);
    assertThat(r.getRemoteUpdate("refs/meta/config").getStatus())
        .isNotEqualTo(RemoteRefUpdate.Status.OK);
    assertThat(r.getMessages()).contains("regular expressions are not supported");

    repo.reset("refs/meta/config");
    repo.branch("HEAD")
        .commit()
        .author(admin.newIdent())
        .committer(admin.newIdent())
        .message("missing target")
        .add("branch-review.config", DEFAULT_CONFIG.replace("refs/heads/master", "refs/heads/nope"))
        .create();
    r = push(repo, "HEAD:refs/meta/config", false);
    assertThat(r.getMessages()).contains("targetBranch refs/heads/nope does not exist");

    repo.reset("refs/meta/config");
    repo.branch("HEAD")
        .commit()
        .author(admin.newIdent())
        .committer(admin.newIdent())
        .message("good config")
        .add("branch-review.config", DEFAULT_CONFIG)
        .create();
    r = push(repo, "HEAD:refs/meta/config", false);
    assertOk(r, "refs/meta/config");
  }

  @Test
  public void configAndSourceInOnePushAreNotSynchronized() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(admin);
    RevCommit feature = commits(repo, "a", 1);
    repo.git().fetch().setRemote("origin").setRefSpecs("refs/meta/config:refs/meta/config").call();
    repo.reset("refs/meta/config");
    RevCommit cfg =
        repo.branch("HEAD")
            .commit()
            .author(admin.newIdent())
            .committer(admin.newIdent())
            .message("touch config")
            .add("branch-review.config", DEFAULT_CONFIG + "  deleteSourceBranchOnSubmit = true\n")
            .create();
    PushResult r =
        repo.git()
            .push()
            .setRemote("origin")
            .setRefSpecs(
                new org.eclipse.jgit.transport.RefSpec(feature.name() + ":" + FEATURE),
                new org.eclipse.jgit.transport.RefSpec(cfg.name() + ":refs/meta/config"))
            .call()
            .iterator()
            .next();
    assertOk(r, FEATURE);
    assertThat(joined(r)).contains("refs/meta/config changed in the same push; not synchronized");
    assertThat(changes()).isEmpty();
    syncViaRest(admin, FEATURE, new PostSync.Input());
    assertThat(changes()).hasSize(1);
  }

  @Test
  public void hiddenSourceBranchIsNotDisclosed() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(admin);
    commits(repo, "a", 1);
    push(repo, "refs/heads/feature/secret");
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            block(Permission.READ)
                .ref("refs/heads/feature/secret")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    status(user, "refs/heads/feature/secret").assertNotFound();
    status(admin, "refs/heads/feature/secret").assertOK();
    var resp =
        restSession(user)
            .post(
                "/projects/" + project.get() + "/branch-review~sync",
                input("refs/heads/feature/secret"));
    resp.assertNotFound();
  }

  @Test
  public void privateChangeIsNotDisclosedInStatus() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(admin);
    commits(repo, "a", 1);
    push(repo, FEATURE);
    gApi.changes().id(project.get(), onlyChange()).setPrivate(true);
    String body = status(user, FEATURE).getEntityContent();
    assertThat(body).doesNotContain("change_number");
    assertThat(body).doesNotContain("change_url");
  }

  @Test
  public void invisibleChangeIsNotRevealedThroughPushOrStatus() throws Exception {
    enable();
    TestRepository<InMemoryRepository> adminRepo = cloneAs(admin);
    commits(adminRepo, "a", 1);
    push(adminRepo, FEATURE);
    int n = onlyChange();
    serverCommit("refs/heads/secret-stable", "s.txt", "s\n");
    gApi.changes().id(project.get(), n).setPrivate(true);
    gApi.changes().id(project.get(), n).move("secret-stable");
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            block(Permission.READ)
                .ref("refs/heads/secret-stable")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    // The admin's own push records the moved state (and its destination) in the lifecycle.
    commits(adminRepo, "b", 1);
    assertThat(joined(push(adminRepo, FEATURE))).contains("secret-stable");

    TestRepository<InMemoryRepository> repo = cloneAs(user);
    repo.git().fetch().setRemote("origin").setRefSpecs("+" + FEATURE + ":" + FEATURE).call();
    repo.reset(refValue(FEATURE));
    commits(repo, "c", 1);
    PushResult r = push(repo, FEATURE);
    assertOk(r, FEATURE);
    String out = joined(r);
    assertThat(out).contains("not visible to you");
    assertThat(out).doesNotContain("secret-stable");
    assertThat(out).doesNotContain("/+/" + n);
    assertThat(out).doesNotContain("Change " + n);
    String status = status(user, FEATURE).getEntityContent();
    assertThat(status).doesNotContain("secret-stable");
    assertThat(status).doesNotContain("change_number");
  }

  @Test
  public void syncRequiresPushPermissionOnSource() throws Exception {
    enable();
    TestRepository<InMemoryRepository> repo = cloneAs(admin);
    commits(repo, "a", 1);
    push(repo, "refs/heads/feature/admin-only");
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            block(Permission.PUSH)
                .ref("refs/heads/feature/admin-only")
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    restSession(user)
        .post(
            "/projects/" + project.get() + "/branch-review~sync",
            input("refs/heads/feature/admin-only"))
        .assertForbidden();
  }

  private static PostSync.Input input(String ref) {
    PostSync.Input in = new PostSync.Input();
    in.ref = ref;
    return in;
  }
}
