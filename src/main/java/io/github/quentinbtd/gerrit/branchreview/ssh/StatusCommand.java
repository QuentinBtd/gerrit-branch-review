// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.ssh;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import io.github.quentinbtd.gerrit.branchreview.api.BranchStatus;
import io.github.quentinbtd.gerrit.branchreview.api.StatusInfo;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import org.kohsuke.args4j.Argument;

@CommandMetaData(name = "status", description = "Show the review lifecycle of a source branch")
public class StatusCommand extends SshCommand {
  @Argument(index = 0, required = true, metaVar = "PROJECT")
  private String project;

  @Argument(index = 1, required = true, metaVar = "BRANCH")
  private String ref;

  @Inject private BranchStatus status;
  @Inject private CurrentUser user;

  @Override
  protected void run() throws UnloggedFailure, Exception {
    try {
      StatusInfo info = status.status(Project.nameKey(project), ref, user);
      stdout.print(StateStore.toJson(info));
      stdout.print('\n');
    } catch (RestApiException e) {
      throw die(e.getMessage());
    }
  }
}
