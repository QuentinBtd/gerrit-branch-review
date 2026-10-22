// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package com.google.gerrit.sshd;

/** Test-only access to Gerrit's own sshd command-line splitter (package-private upstream). */
public final class SshSplitForTests {
  private SshSplitForTests() {}

  public static String[] split(String commandLine) {
    return CommandFactoryProvider.split(commandLine);
  }
}
