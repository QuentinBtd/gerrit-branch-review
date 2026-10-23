// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.validation;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.git.validators.ValidationMessage;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import io.github.quentinbtd.gerrit.branchreview.config.ConfigParser;
import io.github.quentinbtd.gerrit.branchreview.config.ProjectConfigLoader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Rejects {@code refs/meta/config} commits (direct pushes and uploads for review) whose {@code
 * branch-review.config} is invalid or would make the project's effective configuration (merged with
 * its ancestors' files) invalid, and any commit targeting the plugin state namespace. Target
 * branches missing from the project only produce a warning: a parent project, All-Projects
 * included, usually has no branches of its own.
 */
@Singleton
public class ConfigValidator implements CommitValidationListener {
  /** Problems with a config commit: errors reject it, warnings are only reported. */
  public record Result(ImmutableList<String> errors, ImmutableList<String> warnings) {}

  private static final String PROJECT_CONFIG = "project.config";

  private final GitRepositoryManager repoManager;
  private final ProjectConfigLoader configLoader;
  private final AllProjectsName allProjects;

  @Inject
  ConfigValidator(
      GitRepositoryManager repoManager,
      ProjectConfigLoader configLoader,
      AllProjectsName allProjects) {
    this.repoManager = repoManager;
    this.configLoader = configLoader;
    this.allProjects = allProjects;
  }

  @Override
  public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent event)
      throws CommitValidationException {
    String dest = event.refName;
    if (dest != null && dest.startsWith(BranchReviewConfig.STATE_NAMESPACE)) {
      throw new CommitValidationException(
          "refs under "
              + BranchReviewConfig.STATE_NAMESPACE
              + " are managed by the branch-review plugin");
    }
    if (!RefNames.REFS_CONFIG.equals(dest)) {
      return List.of();
    }
    Result result =
        validate(event.getProjectNameKey(), event.revWalk.getObjectReader(), event.commit);
    if (!result.errors().isEmpty()) {
      List<CommitValidationMessage> msgs = new ArrayList<>();
      for (String e : result.errors()) {
        msgs.add(
            new CommitValidationMessage(
                ConfigParser.FILE_NAME + ": " + e, ValidationMessage.Type.ERROR));
      }
      throw new CommitValidationException("invalid " + ConfigParser.FILE_NAME, msgs);
    }
    List<CommitValidationMessage> msgs = new ArrayList<>();
    for (String w : result.warnings()) {
      msgs.add(
          new CommitValidationMessage(
              ConfigParser.FILE_NAME + ": " + w, ValidationMessage.Type.WARNING));
    }
    return msgs;
  }

  /**
   * Validates the effective configuration {@code commit} would give {@code project}: its own file
   * merged with the current files of the parent chain, the parent being read from the {@code
   * project.config} of {@code commit}. An absent file is checked too: removing it exposes the
   * inherited configuration, which must then be valid on its own.
   */
  public Result validate(Project.NameKey project, ObjectReader reader, RevCommit commit)
      throws CommitValidationException {
    BranchReviewConfig cfg;
    try {
      ConfigParser.Layer own =
          ConfigParser.parseLayer(ProjectConfigLoader.readFile(reader, commit), project.get());
      cfg = configLoader.loadWith(parentOf(project, reader, commit), own);
    } catch (IOException | ConfigInvalidException e) {
      throw new CommitValidationException("cannot read " + ConfigParser.FILE_NAME, e);
    }
    if (cfg.status() == BranchReviewConfig.Status.INVALID) {
      return new Result(cfg.errors(), ImmutableList.of());
    }
    if (!cfg.isEnabled()) {
      return new Result(ImmutableList.of(), ImmutableList.of());
    }
    try (Repository repo = repoManager.openRepository(project)) {
      ImmutableList.Builder<String> missing = ImmutableList.builder();
      for (String target : cfg.targets()) {
        if (repo.exactRef(target) == null) {
          missing.add(
              "target branch "
                  + target
                  + " does not exist in "
                  + project.get()
                  + "; pushes routed to it will not be reviewed until it is created");
        }
      }
      return new Result(ImmutableList.of(), missing.build());
    } catch (IOException e) {
      throw new CommitValidationException("cannot verify the target branches", e);
    }
  }

  private Optional<Project.NameKey> parentOf(
      Project.NameKey project, ObjectReader reader, RevCommit commit)
      throws IOException, ConfigInvalidException {
    if (project.equals(allProjects)) {
      return Optional.empty();
    }
    String parent = null;
    try (TreeWalk tw = TreeWalk.forPath(reader, PROJECT_CONFIG, commit.getTree())) {
      if (tw != null) {
        Config pc = new Config();
        pc.fromText(
            new String(
                reader.open(tw.getObjectId(0), Constants.OBJ_BLOB).getCachedBytes(1024 * 1024),
                UTF_8));
        parent = pc.getString("access", null, "inheritFrom");
      }
    }
    return Optional.of(
        parent == null || parent.isBlank() ? allProjects : Project.nameKey(parent.strip()));
  }
}
