// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.api;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.common.PluginDefinedInfo;
import com.google.gerrit.extensions.common.WebLinkInfo;
import com.google.gerrit.server.DynamicOptions;
import com.google.gerrit.server.WebLinks;
import com.google.gerrit.server.change.ChangePluginDefinedInfoFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.state.ChangeLink;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Repository;

/**
 * Adds the source branch to {@code ChangeInfo.plugins} of Changes created by the plugin, for the
 * change-page metadata item. Read only from the durable change link, and only shown to users who
 * can read the source branch (a single-commit squash keeps the original message, so the Change
 * itself does not always reveal the branch name). While the branch exists, the first branch web
 * link (e.g. gitiles) is added as {@code source_branch_url}.
 */
@Singleton
public class SourceBranchAttribute implements ChangePluginDefinedInfoFactory {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  /**
   * JSON: {@code {"name": "branch-review", "source_branch": "refs/heads/feature/x",
   * "source_branch_url": "/plugins/gitiles/p/+/refs/heads/feature/x"}}.
   */
  public static class Info extends PluginDefinedInfo {
    public String sourceBranch;
    public String sourceBranchUrl;
  }

  private final GitRepositoryManager repoManager;
  private final StateStore store;
  private final PermissionBackend permissionBackend;
  private final WebLinks webLinks;

  @Inject
  SourceBranchAttribute(
      GitRepositoryManager repoManager,
      StateStore store,
      PermissionBackend permissionBackend,
      WebLinks webLinks) {
    this.repoManager = repoManager;
    this.store = store;
    this.permissionBackend = permissionBackend;
    this.webLinks = webLinks;
  }

  @Override
  public Map<Change.Id, PluginDefinedInfo> createPluginDefinedInfos(
      Collection<ChangeData> cds, DynamicOptions.BeanProvider beanProvider, String plugin) {
    Map<Change.Id, PluginDefinedInfo> out = new HashMap<>();
    Map<Project.NameKey, List<ChangeData>> byProject =
        cds.stream().collect(Collectors.groupingBy(ChangeData::project));
    for (Map.Entry<Project.NameKey, List<ChangeData>> e : byProject.entrySet()) {
      try (Repository repo = repoManager.openRepository(e.getKey())) {
        for (ChangeData cd : e.getValue()) {
          StateStore.Loaded<ChangeLink> link = store.loadLink(repo, cd.getId().get());
          String ref = link.exists() ? link.value().sourceRef : null;
          if (ref != null
              && permissionBackend
                  .currentUser()
                  .project(e.getKey())
                  .ref(ref)
                  .test(RefPermission.READ)) {
            Info info = new Info();
            info.sourceBranch = ref;
            if (repo.exactRef(ref) != null) {
              info.sourceBranchUrl =
                  webLinks.getBranchLinks(e.getKey().get(), ref).stream()
                      .map((WebLinkInfo l) -> l.url)
                      .findFirst()
                      .orElse(null);
            }
            out.put(cd.getId(), info);
          }
        }
      } catch (IOException | PermissionBackendException ex) {
        // Display only: never fail a change query because the link cannot be read.
        log.atWarning().withCause(ex).log("cannot read change links of %s", e.getKey());
      }
    }
    return out;
  }
}
