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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Loads the effective {@code branch-review.config} of a project: the files at the root of the
 * {@code refs/meta/config} of every project in its parent chain, merged from All-Projects down to
 * the project ({@link ConfigParser#merge}).
 *
 * <p>Each file is read from the exact {@code refs/meta/config} revision of the cached {@link
 * ProjectState}; parsed files are cached by (project, revision) and merged results by the whole
 * chain of (project, revision) pairs. A config update in any ancestor produces a new revision and
 * therefore a new chain key; Gerrit's project cache eviction on {@code refs/meta/config} updates
 * keeps the revisions current.
 */
@Singleton
public class ProjectConfigLoader {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  private record Key(Project.NameKey project, ObjectId revision) {}

  private final ProjectCache projectCache;
  private final GitRepositoryManager repoManager;
  private final Cache<Key, ConfigParser.Layer> layers =
      CacheBuilder.newBuilder().maximumSize(2000).build();
  private final Cache<List<Key>, BranchReviewConfig> merged =
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
    List<Key> chain = chain(state.get());
    BranchReviewConfig cached = merged.getIfPresent(chain);
    if (cached != null) {
      return cached;
    }
    BranchReviewConfig cfg;
    try {
      cfg = ConfigParser.merge(layers(chain));
    } catch (IOException e) {
      log.atWarning().withCause(e).log("cannot read %s for %s", ConfigParser.FILE_NAME, project);
      // Not cached: a transient read error must not stick.
      return BranchReviewConfig.invalid(
          ImmutableList.of("cannot read " + ConfigParser.FILE_NAME + ": " + e.getMessage()));
    }
    merged.put(chain, cfg);
    return cfg;
  }

  /**
   * The effective configuration {@code own} would give a project whose parent is {@code parent}
   * (absent for All-Projects), with the ancestors' current files.
   */
  public BranchReviewConfig loadWith(Optional<Project.NameKey> parent, ConfigParser.Layer own)
      throws IOException {
    List<ConfigParser.Layer> all = new ArrayList<>();
    if (parent.isPresent()) {
      Optional<ProjectState> state = projectCache.get(parent.get());
      if (state.isPresent()) {
        all.addAll(layers(chain(state.get())));
      }
    }
    all.add(own);
    return ConfigParser.merge(all);
  }

  /** (project, revision) from All-Projects down to {@code state}; projects without one skipped. */
  private static List<Key> chain(ProjectState state) {
    List<Key> chain = new ArrayList<>();
    for (ProjectState s : state.treeInOrder()) {
      s.getConfig().getRevision().ifPresent(r -> chain.add(new Key(s.getNameKey(), r.copy())));
    }
    return List.copyOf(chain);
  }

  private List<ConfigParser.Layer> layers(List<Key> chain) throws IOException {
    List<ConfigParser.Layer> out = new ArrayList<>(chain.size());
    for (Key k : chain) {
      ConfigParser.Layer layer = layers.getIfPresent(k);
      if (layer == null) {
        try (Repository repo = repoManager.openRepository(k.project())) {
          layer = ConfigParser.parseLayer(readFile(repo, k.revision()), k.project().get());
        }
        layers.put(k, layer);
      }
      out.add(layer);
    }
    return out;
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
