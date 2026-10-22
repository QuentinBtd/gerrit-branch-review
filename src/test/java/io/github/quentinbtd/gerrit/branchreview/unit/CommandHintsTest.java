// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.unit;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gerrit.sshd.SshSplitForTests;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.quentinbtd.gerrit.branchreview.sync.CommandHints;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/**
 * Feeds the generated repair commands to a real POSIX shell (with ssh/curl replaced by a printer)
 * and the remote part to Gerrit's own sshd command-line splitter.
 */
public class CommandHintsTest {
  private static final Optional<String> SSH = Optional.of("ssh -p 29418 gerrit.example.org");
  private static final Optional<String> WEB = Optional.of("https://gerrit.example.org/r/");

  /** Runs {@code command} with its first word replaced by a printer; returns the argv it sees. */
  private static List<String> shellArgs(String command, String program) throws Exception {
    assertThat(command).startsWith(program + " ");
    String script =
        "printargs() { for a in \"$@\"; do printf '%s\\0' \"$a\"; done; }; printargs"
            + command.substring(program.length());
    Process p = new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(false).start();
    String out = new String(p.getInputStream().readAllBytes(), UTF_8);
    assertThat(p.waitFor()).isEqualTo(0);
    List<String> args = new ArrayList<>(Arrays.asList(out.split("\0", -1)));
    args.remove(args.size() - 1);
    return args;
  }

  @Test
  public void ordinaryNamesStayReadable() {
    assertThat(CommandHints.syncCommand(SSH, WEB, "qbr", "refs/heads/chore/test-plugin", null))
        .isEqualTo(
            "ssh -p 29418 gerrit.example.org branch-review sync qbr refs/heads/chore/test-plugin");
    assertThat(
            CommandHints.syncCommand(
                SSH, WEB, "team/app", "refs/heads/feature/x", "--accept-divergence"))
        .isEqualTo(
            "ssh -p 29418 gerrit.example.org branch-review sync --accept-divergence team/app"
                + " refs/heads/feature/x");
  }

  @Test
  public void hostileNamesReachGerritAsSingleArguments() throws Exception {
    String[][] cases = {
      {"qbr", "refs/heads/feature/x;id"},
      {"my project", "refs/heads/a$(touch pwned)"},
      {"q'uote", "refs/heads/b`id`&&echo"},
      {"back\\slash", "refs/heads/c\"d|e>f"},
    };
    for (String[] c : cases) {
      for (String cmd :
          List.of(
              CommandHints.syncCommand(SSH, WEB, c[0], c[1], "--resume"),
              CommandHints.statusCommand(SSH, WEB, c[0], c[1]))) {
        List<String> local = shellArgs(cmd, "ssh");
        // ssh -p 29418 host <one remote command line>
        assertThat(local).hasSize(4);
        String[] remote = SshSplitForTests.split(local.get(3));
        assertThat(remote[remote.length - 2]).isEqualTo(c[0]);
        assertThat(remote[remote.length - 1]).isEqualTo(c[1]);
        assertThat(remote[0]).isEqualTo("branch-review");
      }
    }
  }

  @Test
  public void restFormIsQuotedAndEncoded() throws Exception {
    String ref = "refs/heads/x';id;'\"";
    String cmd =
        CommandHints.syncCommand(Optional.empty(), WEB, "a b/c", ref, "--accept-divergence");
    List<String> args = shellArgs(cmd, "curl");
    String body = args.get(args.indexOf("-d") + 1);
    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
    assertThat(json.get("ref").getAsString()).isEqualTo(ref);
    assertThat(json.get("accept_divergence").getAsBoolean()).isTrue();
    assertThat(args.get(args.size() - 1))
        .isEqualTo("https://gerrit.example.org/r/a/projects/a%20b%2Fc/branch-review~sync");

    List<String> status =
        shellArgs(CommandHints.statusCommand(Optional.empty(), WEB, "p", ref), "curl");
    assertThat(status).hasSize(1);
    assertThat(status.get(0)).doesNotContain("'");
    assertThat(status.get(0)).doesNotContain(";");
  }
}
