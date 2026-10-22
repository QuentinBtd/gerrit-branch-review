// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.validation;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
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
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * Rejects invalid {@code branch-review.config} files on {@code refs/meta/config} (direct pushes and
 * uploads for review), and any commit targeting the plugin state namespace.
 */
@Singleton
public class ConfigValidator implements CommitValidationListener {
  private final GitRepositoryManager repoManager;

  @Inject
  ConfigValidator(GitRepositoryManager repoManager) {
    this.repoManager = repoManager;
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
    List<String> errors =
        validate(event.getProjectNameKey(), event.revWalk.getObjectReader(), event.commit);
    if (!errors.isEmpty()) {
      List<CommitValidationMessage> msgs = new ArrayList<>();
      for (String e : errors) {
        msgs.add(
            new CommitValidationMessage(
                ConfigParser.FILE_NAME + ": " + e, ValidationMessage.Type.ERROR));
      }
      throw new CommitValidationException("invalid " + ConfigParser.FILE_NAME, msgs);
    }
    return List.of();
  }

  /** Validates the file in {@code commit}; an absent file is valid (plugin disabled). */
  public ImmutableList<String> validate(
      Project.NameKey project, ObjectReader reader, RevCommit commit)
      throws CommitValidationException {
    String text;
    try {
      text = ProjectConfigLoader.readFile(reader, commit);
    } catch (IOException e) {
      throw new CommitValidationException("cannot read " + ConfigParser.FILE_NAME, e);
    }
    BranchReviewConfig cfg = ConfigParser.parseText(text);
    if (cfg.status() == BranchReviewConfig.Status.INVALID) {
      return cfg.errors();
    }
    if (cfg.isEnabled()) {
      try (Repository repo = repoManager.openRepository(project)) {
        if (repo.exactRef(cfg.targetBranch()) == null) {
          return ImmutableList.of("targetBranch " + cfg.targetBranch() + " does not exist");
        }
      } catch (IOException e) {
        throw new CommitValidationException("cannot verify targetBranch", e);
      }
    }
    return ImmutableList.of();
  }
}
