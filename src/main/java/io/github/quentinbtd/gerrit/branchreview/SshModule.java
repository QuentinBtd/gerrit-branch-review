// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.sshd.PluginCommandModule;
import com.google.inject.Inject;
import io.github.quentinbtd.gerrit.branchreview.ssh.StatusCommand;
import io.github.quentinbtd.gerrit.branchreview.ssh.SyncCommand;

/** {@code ssh <host> branch-review status|sync ...}. */
public class SshModule extends PluginCommandModule {
  @Inject
  SshModule(@PluginName String pluginName) {
    super(pluginName);
  }

  @Override
  protected void configureCommands() {
    command(StatusCommand.class);
    command(SyncCommand.class);
  }
}
