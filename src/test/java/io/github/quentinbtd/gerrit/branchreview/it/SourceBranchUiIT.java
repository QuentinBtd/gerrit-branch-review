// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.it;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;

import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.RestSession;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

public class SourceBranchUiIT extends AbstractBranchReviewIT {
  private static final String FEATURE = "refs/heads/feature/ui-source";

  private JsonObject detail(int number) throws Exception {
    return detail(adminRestSession, number);
  }

  private static JsonObject detail(RestSession session, int number) throws Exception {
    RestResponse r = session.get("/changes/" + number + "/detail");
    r.assertOK();
    return JsonParser.parseReader(r.getReader()).getAsJsonObject();
  }

  @Test
  public void branchReviewChangeCarriesSourceBranch() throws Exception {
    enable();
    var repo = cloneAs(user);
    commits(repo, "a", 1);
    push(repo, FEATURE);
    JsonObject c = detail(onlyChange());
    var plugins = c.getAsJsonArray("plugins");
    assertThat(plugins).hasSize(1);
    JsonObject info = plugins.get(0).getAsJsonObject();
    assertThat(info.get("name").getAsString()).isEqualTo("branch-review");
    assertThat(info.get("source_branch").getAsString()).isEqualTo(FEATURE);
  }

  @Test
  public void hiddenFromUsersWhoCannotReadTheSourceBranch() throws Exception {
    enable();
    var repo = cloneAs(user);
    commits(repo, "a", 1);
    push(repo, FEATURE);
    int number = onlyChange();
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(Permission.READ).ref(FEATURE).group(SystemGroupBackend.REGISTERED_USERS))
        .update();
    assertThat(detail(userRestSession, number).has("plugins")).isFalse();
  }

  @Test
  public void ordinaryChangeHasNoSourceBranch() throws Exception {
    int number = createChange().getChange().getId().get();
    JsonObject c = detail(number);
    assertThat(c.has("plugins")).isFalse();
  }

  @Test
  public void uiScriptIsRegistered() throws Exception {
    assertThat(gApi.config().server().getInfo().plugin.jsResourcePaths)
        .contains("plugins/branch-review/static/branch-review.js");
  }
}
