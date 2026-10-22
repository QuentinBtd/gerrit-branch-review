// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.sync;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Optional;
import org.eclipse.jgit.lib.Config;

/**
 * Builds the repair commands shown to users, with this server's real SSH address.
 *
 * <p>The address is {@code sshd.advertisedAddress} if set, otherwise {@code sshd.listenAddress}; a
 * wildcard host is replaced by the host of {@code gerrit.canonicalWebUrl}. When SSH is disabled the
 * equivalent REST call is shown instead.
 */
@Singleton
public class CommandHints {
  private static final int DEFAULT_SSH_PORT = 29418;

  private final Config cfg;
  private final DynamicItem<UrlFormatter> urlFormatter;

  @Inject
  CommandHints(@GerritServerConfig Config cfg, DynamicItem<UrlFormatter> urlFormatter) {
    this.cfg = cfg;
    this.urlFormatter = urlFormatter;
  }

  /** {@code ssh -p <port> <host> branch-review sync [flag] <project> <ref>}, or the REST form. */
  public String sync(Project.NameKey project, String ref, String flag) {
    Optional<String> ssh = ssh();
    if (ssh.isPresent()) {
      return ssh.get()
          + " branch-review sync "
          + (flag == null ? "" : flag + " ")
          + project.get()
          + " "
          + ref;
    }
    String option = flag == null ? "" : ",\"" + flag.substring(2).replace('-', '_') + "\":true";
    return "curl -X POST -H 'Content-Type: application/json' -d '{\"ref\":\""
        + ref
        + "\""
        + option
        + "}' "
        + web().orElse("<gerrit-url>/")
        + "a/projects/"
        + project.get().replace("/", "%2F")
        + "/branch-review~sync";
  }

  /** {@code ssh -p <port> <host> branch-review status <project> <ref>}, or the REST form. */
  public String status(Project.NameKey project, String ref) {
    return ssh()
        .map(s -> s + " branch-review status " + project.get() + " " + ref)
        .orElse(
            "curl "
                + web().orElse("<gerrit-url>/")
                + "a/projects/"
                + project.get().replace("/", "%2F")
                + "/branch-review~status?ref="
                + ref);
  }

  /** {@code ssh -p <port> <host>}, empty if SSH is disabled. */
  public Optional<String> ssh() {
    String[] advertised = cfg.getStringList("sshd", null, "advertisedAddress");
    String[] listen = cfg.getStringList("sshd", null, "listenAddress");
    if (advertised.length == 0
        && listen.length > 0
        && Arrays.stream(listen).allMatch(a -> "off".equalsIgnoreCase(a.trim()))) {
      return Optional.empty();
    }
    String addr = advertised.length > 0 ? advertised[0] : listen.length > 0 ? listen[0] : "*:29418";
    String host = addr;
    int port = DEFAULT_SSH_PORT;
    int colon = addr.lastIndexOf(':');
    if (colon >= 0 && !addr.endsWith("]")) {
      host = addr.substring(0, colon);
      try {
        port = Integer.parseInt(addr.substring(colon + 1));
      } catch (NumberFormatException e) {
        port = DEFAULT_SSH_PORT;
      }
    }
    if (host.isEmpty() || host.equals("*") || host.equals("0.0.0.0") || host.equals("[::]")) {
      host = web().map(CommandHints::hostOf).orElse("<gerrit-host>");
    }
    return Optional.of(port == 22 ? "ssh " + host : "ssh -p " + port + " " + host);
  }

  private Optional<String> web() {
    return urlFormatter.get().getWebUrl();
  }

  private static String hostOf(String url) {
    try {
      String h = new URI(url).getHost();
      return h == null ? "<gerrit-host>" : h;
    } catch (URISyntaxException e) {
      return "<gerrit-host>";
    }
  }
}
