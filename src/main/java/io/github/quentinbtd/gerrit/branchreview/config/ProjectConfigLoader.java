// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Optional;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Loads {@code branch-review.config} from the root of a project's {@code refs/meta/config}.
 *
 * <p>The file is read from the exact {@code refs/meta/config} revision of the cached {@link
 * ProjectState}, and parsed results are cached by (project, revision). A config update produces a
 * new revision and therefore a new cache key; Gerrit's project cache eviction on {@code
 * refs/meta/config} updates keeps the revision current. There is deliberately no inheritance from
 * parent projects.
 */
@Singleton
public class ProjectConfigLoader {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  private record Key(Project.NameKey project, ObjectId revision) {}

  private final ProjectCache projectCache;
  private final GitRepositoryManager repoManager;
  private final Cache<Key, BranchReviewConfig> cache =
      CacheBuilder.newBuilder().maximumSize(2000).build();

  @Inject
  ProjectConfigLoader(ProjectCache projectCache, GitRepositoryManager repoManager) {
    this.projectCache = projectCache;
    this.repoManager = repoManager;
  }

  public BranchReviewConfig load(Project.NameKey project) {
    Optional<ProjectState> state = projectCache.get(project);
    if (state.isEmpty()) {
      return BranchReviewConfig.disabled();
    }
    Optional<ObjectId> rev = state.get().getConfig().getRevision();
    if (rev.isEmpty()) {
      return BranchReviewConfig.disabled();
    }
    Key key = new Key(project, rev.get().copy());
    BranchReviewConfig cached = cache.getIfPresent(key);
    if (cached != null) {
      return cached;
    }
    BranchReviewConfig cfg;
    try (Repository repo = repoManager.openRepository(project)) {
      cfg = ConfigParser.parseText(readFile(repo, rev.get()));
    } catch (IOException e) {
      log.atWarning().withCause(e).log("cannot read %s of %s", ConfigParser.FILE_NAME, project);
      // Not cached: a transient read error must not stick.
      return BranchReviewConfig.invalid(
          ImmutableList.of("cannot read " + ConfigParser.FILE_NAME + ": " + e.getMessage()));
    }
    cache.put(key, cfg);
    return cfg;
  }

  /** Reads the file at {@code commit}, or returns {@code null} if absent. */
  public static String readFile(Repository repo, ObjectId commit) throws IOException {
    try (RevWalk rw = new RevWalk(repo)) {
      return readFile(rw.getObjectReader(), rw.parseCommit(commit));
    }
  }

  public static String readFile(ObjectReader reader, RevCommit commit) throws IOException {
    try (TreeWalk tw = TreeWalk.forPath(reader, ConfigParser.FILE_NAME, commit.getTree())) {
      if (tw == null) {
        return null;
      }
      return new String(
          reader.open(tw.getObjectId(0), Constants.OBJ_BLOB).getCachedBytes(256 * 1024), UTF_8);
    }
  }

  public static boolean isConfigRef(String ref) {
    return RefNames.REFS_CONFIG.equals(ref);
  }
}
