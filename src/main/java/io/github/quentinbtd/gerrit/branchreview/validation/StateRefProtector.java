// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.validation;

import com.google.gerrit.server.events.RefReceivedEvent;
import com.google.gerrit.server.git.validators.RefOperationValidationListener;
import com.google.gerrit.server.git.validators.ValidationMessage;
import com.google.gerrit.server.validators.ValidationException;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import java.util.List;

/**
 * Rejects user ref operations (push create/update/delete, REST branch APIs) on the plugin state
 * namespace. The plugin itself writes these refs directly and is not subject to this check.
 */
@Singleton
public class StateRefProtector implements RefOperationValidationListener {
  @Override
  public List<ValidationMessage> onRefOperation(RefReceivedEvent event) throws ValidationException {
    if (event.getRefName().startsWith(BranchReviewConfig.STATE_NAMESPACE)) {
      throw new ValidationException(
          "refs under "
              + BranchReviewConfig.STATE_NAMESPACE
              + " are managed by the branch-review plugin");
    }
    return List.of();
  }
}
