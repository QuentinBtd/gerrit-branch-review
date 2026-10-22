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
import io.github.quentinbtd.gerrit.branchreview.sync.SyncResult;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncService;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

@CommandMetaData(
    name = "sync",
    description = "Synchronize a source branch to its review Change (idempotent)")
public class SyncCommand extends SshCommand {
  @Argument(index = 0, required = true, metaVar = "PROJECT")
  private String project;

  @Argument(index = 1, required = true, metaVar = "BRANCH")
  private String ref;

  @Option(name = "--accept-divergence", usage = "overwrite a patch set not produced by the plugin")
  private boolean acceptDivergence;

  @Option(
      name = "--new-lifecycle",
      usage = "start a new Change after the previous one was abandoned")
  private boolean newLifecycle;

  @Option(name = "--resume", usage = "continue the Change of a deleted and recreated branch")
  private boolean resume;

  @Option(name = "--reset-text", usage = "regenerate the subject and body, dropping manual edits")
  private boolean resetText;

  @Inject private BranchStatus status;
  @Inject private CurrentUser user;

  @Override
  protected void run() throws UnloggedFailure, Exception {
    try {
      SyncResult r =
          status.sync(
              Project.nameKey(project),
              ref,
              user,
              new SyncService.Options(acceptDivergence, newLifecycle, resume, resetText));
      if (r.kind() == SyncResult.Kind.IGNORED) {
        stdout.print("branch-review: " + ref + " is not managed by branch-review\n");
      }
      for (String line : r.render()) {
        stdout.print("branch-review: " + line + "\n");
      }
    } catch (RestApiException e) {
      throw die(e.getMessage());
    }
  }
}
