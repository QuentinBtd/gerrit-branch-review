// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.api;

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import org.kohsuke.args4j.Option;

/** {@code GET /projects/{project}/branch-review~status?ref=refs/heads/feature/x}. */
public class GetStatus implements RestReadView<ProjectResource> {
  private final BranchStatus status;

  @Option(name = "--ref", aliases = "-r", usage = "source branch", metaVar = "REF")
  private String ref;

  @Inject
  GetStatus(BranchStatus status) {
    this.status = status;
  }

  @Override
  public Response<StatusInfo> apply(ProjectResource rsrc) throws Exception {
    return Response.ok(status.status(rsrc.getNameKey(), ref, rsrc.getUser()));
  }
}
