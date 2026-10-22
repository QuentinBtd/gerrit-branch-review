// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.sync;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
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
    return syncCommand(ssh(), web(), project.get(), ref, flag);
  }

  /** {@code ssh -p <port> <host> branch-review status <project> <ref>}, or the REST form. */
  public String status(Project.NameKey project, String ref) {
    return statusCommand(ssh(), web(), project.get(), ref);
  }

  // The commands below are pasted into a terminal by users, and project and ref names are
  // user-controlled (refs/heads/x;id is a valid ref). Every argument is therefore quoted for the
  // remote side (Gerrit's sshd splits command lines with POSIX quoting rules) and the whole
  // remote command again for the local shell. Names made only of safe characters stay readable.

  public static String syncCommand(
      Optional<String> ssh, Optional<String> web, String project, String ref, String flag) {
    if (ssh.isPresent()) {
      String remote =
          "branch-review sync "
              + (flag == null ? "" : shellQuote(flag) + " ")
              + shellQuote(project)
              + " "
              + shellQuote(ref);
      return ssh.get() + " " + quoteRemote(remote);
    }
    JsonObject body = new JsonObject();
    body.addProperty("ref", ref);
    if (flag != null) {
      body.addProperty(flag.substring(2).replace('-', '_'), true);
    }
    return "curl -X POST -H 'Content-Type: application/json' -d "
        + shellQuote(body.toString())
        + " "
        + shellQuote(projectUrl(web, project) + "/branch-review~sync");
  }

  public static String statusCommand(
      Optional<String> ssh, Optional<String> web, String project, String ref) {
    if (ssh.isPresent()) {
      return ssh.get()
          + " "
          + quoteRemote("branch-review status " + shellQuote(project) + " " + shellQuote(ref));
    }
    return "curl "
        + shellQuote(projectUrl(web, project) + "/branch-review~status?ref=" + encode(ref));
  }

  private static String projectUrl(Optional<String> web, String project) {
    return web.orElse("<gerrit-url>/") + "a/projects/" + encode(project);
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_./@%+=:,~-]+");

  private static final Pattern SAFE_WORDS = Pattern.compile("[A-Za-z0-9_./@%+=:,~ -]+");

  /** Quotes a remote command line for the local shell unless it only has safe words. */
  private static String quoteRemote(String remote) {
    return SAFE_WORDS.matcher(remote).matches() ? remote : shellQuote(remote);
  }

  /** POSIX shell quoting; returns safe words unchanged. */
  public static String shellQuote(String s) {
    if (SAFE.matcher(s).matches()) {
      return s;
    }
    return "'" + s.replace("'", "'\\''") + "'";
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
    return Optional.of(
        port == 22 ? "ssh " + shellQuote(host) : "ssh -p " + port + " " + shellQuote(host));
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
