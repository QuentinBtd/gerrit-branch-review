// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.api;

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncResult;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncService;
import java.util.List;

/** {@code POST /projects/{project}/branch-review~sync}: explicit, idempotent resynchronization. */
public class PostSync implements RestModifyView<ProjectResource, PostSync.Input> {
  public static class Input {
    public String ref;
    public boolean acceptDivergence;
    public boolean newLifecycle;
    public boolean resume;
    public boolean resetText;
  }

  public static class Output {
    public String result;
    public String sourceRef;
    public Integer changeNumber;
    public Integer patchSet;
    public String changeUrl;
    public List<String> messages;
  }

  private final BranchStatus status;

  @Inject
  PostSync(BranchStatus status) {
    this.status = status;
  }

  @Override
  public Response<Output> apply(ProjectResource rsrc, Input in) throws Exception {
    Input input = in == null ? new Input() : in;
    SyncResult r =
        status.sync(
            rsrc.getNameKey(),
            input.ref,
            rsrc.getUser(),
            new SyncService.Options(
                input.acceptDivergence, input.newLifecycle, input.resume, input.resetText));
    Output o = new Output();
    o.result = r.kind().metric();
    o.sourceRef = r.sourceRef();
    o.changeNumber = r.changeNumber() > 0 ? r.changeNumber() : null;
    o.patchSet = r.patchSet() > 0 ? r.patchSet() : null;
    o.changeUrl = r.url();
    o.messages = r.render();
    return Response.ok(o);
  }
}
