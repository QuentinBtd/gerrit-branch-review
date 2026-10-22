#!/usr/bin/env python3
# Copyright (C) 2026 The branch-review Authors
# SPDX-License-Identifier: Apache-2.0
"""Black-box tests: a real Gerrit container, the packaged plugin JAR, real git clients.

Each run starts one disposable container from the pinned official image, installs the JAR
exactly as an operator would (copied to $site/plugins/branch-review.jar), creates disposable
users/keys/projects and drives `git push` over SSH and smart HTTP. Ports are bound to
127.0.0.1 only. The container is removed afterwards unless --keep is given.

Usage: blackbox.py --series 3.14 --jar dist/branch-review-X-gerrit-3.14.jar --out DIR
"""

import argparse
import base64
import http.cookiejar
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
LABEL = "io.github.quentinbtd.branch-review.it"
PREFIX = "/r"  # Exercise canonical URLs below a path prefix.
PASSWORD = "secret-" + uuid.uuid4().hex[:12]


def sh(cmd, check=True, env=None, cwd=None, timeout=300):
    p = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=cwd, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(
            "command failed (%d): %s\nstdout:\n%s\nstderr:\n%s"
            % (p.returncode, " ".join(cmd), p.stdout, p.stderr)
        )
    return p


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Gerrit:
    """A disposable Gerrit container."""

    def __init__(self, image, jar, out_dir):
        self.image = image
        self.jar = jar
        self.out_dir = out_dir
        self.name = "branch-review-it-" + uuid.uuid4().hex[:10]
        self.http_port = free_port()
        self.ssh_port = free_port()
        self.base = "http://127.0.0.1:%d%s" % (self.http_port, PREFIX)
        self.canonical = "http://localhost:%d%s/" % (self.http_port, PREFIX)
        self.admin_auth = None

    def start(self):
        sh(
            [
                "docker",
                "create",
                "--name",
                self.name,
                "--label",
                LABEL + "=1",
                "-p",
                "127.0.0.1:%d:8080" % self.http_port,
                "-p",
                "127.0.0.1:%d:29418" % self.ssh_port,
                "-e",
                "CANONICAL_WEB_URL=" + self.canonical,
                "-e",
                "HTTPD_LISTEN_URL=http://*:8080%s/" % PREFIX,
                self.image,
            ]
        )
        sh(["docker", "cp", self.jar, self.name + ":/var/gerrit/plugins/branch-review.jar"])
        sh(["docker", "start", self.name])
        self.wait_ready()

    def restart(self):
        sh(["docker", "restart", self.name], timeout=300)
        self.wait_ready()

    def wait_ready(self, timeout=300):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(self.base + "/config/server/version", timeout=5) as r:
                    if r.status == 200:
                        logs = self.logs()
                        if "Loaded plugin branch-review" in logs:
                            return
            except (urllib.error.URLError, ConnectionError, OSError):
                pass
            state = sh(["docker", "inspect", "-f", "{{.State.Running}}", self.name], check=False)
            if state.stdout.strip() != "true":
                raise RuntimeError("container exited:\n" + self.logs())
            time.sleep(2)
        raise RuntimeError("Gerrit not ready in %ds:\n%s" % (timeout, self.logs()))

    def logs(self):
        return (
            sh(["docker", "logs", self.name], check=False).stdout
            + sh(
                ["docker", "exec", self.name, "sh", "-c", "cat /var/gerrit/logs/error_log 2>/dev/null"],
                check=False,
            ).stdout
        )

    def save_logs(self):
        os.makedirs(self.out_dir, exist_ok=True)
        with open(os.path.join(self.out_dir, "gerrit.log"), "w") as f:
            f.write(self.logs())

    def remove(self):
        sh(["docker", "rm", "-f", "-v", self.name], check=False)

    # -- REST -------------------------------------------------------------------------------

    def rest(self, method, path, body=None, auth=None, expect=(200, 201, 204)):
        url = self.base + "/a" + path
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(url, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        user, pw = auth or self.admin_auth
        req.add_header("Authorization", "Basic " + base64.b64encode(("%s:%s" % (user, pw)).encode()).decode())
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, raw = r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read().decode()
        if status not in expect:
            raise AssertionError("%s %s -> %d: %s" % (method, path, status, raw))
        raw = raw[4:] if raw.startswith(")]}'") else raw
        return status, (json.loads(raw) if raw.strip() else None)

    def rest_raw(self, method, path, data, auth=None):
        req = urllib.request.Request(self.base + "/a" + path, data=data, method=method)
        req.add_header("Content-Type", "application/octet-stream")
        user, pw = auth or self.admin_auth
        req.add_header("Authorization", "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode())
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status

    def bootstrap_admin(self):
        """Creates the first account (which becomes an administrator) via development login."""
        jar = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        # Recent images create an "admin" account during init; older ones need the first
        # development login to create one (the first account becomes an administrator).
        try:
            opener.open(self.base + "/login/?user_name=admin", timeout=30).read()
        except urllib.error.HTTPError:
            opener.open(self.base + "/login/?action=create_account", timeout=30).read()
        opener.open(self.base + "/", timeout=30).read()
        xsrf = next((c.value for c in jar if c.name == "XSRF_TOKEN"), None)
        if not xsrf:
            raise RuntimeError("no XSRF token after development login")

        def call(method, path, body):
            req = urllib.request.Request(self.base + path, data=json.dumps(body).encode(), method=method)
            req.add_header("Content-Type", "application/json")
            req.add_header("X-Gerrit-Auth", xsrf)
            return opener.open(req, timeout=30).read()

        raw = opener.open(self.base + "/accounts/self/detail", timeout=30).read().decode()
        me = json.loads(raw[4:] if raw.startswith(")]}'") else raw)
        username = me.get("username")
        if not username:
            username = "admin"
            call("PUT", "/accounts/self/username", {"username": username})
        call("PUT", "/accounts/self/password.http", {"http_password": PASSWORD})
        self.admin_auth = (username, PASSWORD)

    def create_user(self, name, key_dir):
        key = os.path.join(key_dir, name)
        sh(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", name, "-f", key])
        with open(key + ".pub") as f:
            pub = f.read().strip()
        self.rest(
            "PUT",
            "/accounts/" + name,
            {
                "name": name.capitalize(),
                "email": name + "@example.com",
                "http_password": PASSWORD,
                "ssh_key": pub,
            },
        )
        return User(name, key)


class User:
    def __init__(self, name, key):
        self.name = name
        self.key = key


class GitClient:
    """A git working copy with an isolated configuration, acting as one user."""

    def __init__(self, gerrit, user, project, tmp, transport):
        self.g = gerrit
        self.user = user
        self.dir = tempfile.mkdtemp(dir=tmp, prefix=user.name + "-")
        home = tempfile.mkdtemp(dir=tmp, prefix="home-")
        self.env = {
            "PATH": os.environ["PATH"],
            "HOME": home,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_TERMINAL_PROMPT": "0",
            "LC_ALL": "C",
        }
        if user.key:
            self.env["GIT_SSH_COMMAND"] = (
                "ssh -F /dev/null -i %s -o IdentitiesOnly=yes -o StrictHostKeyChecking=no "
                "-o UserKnownHostsFile=/dev/null -o LogLevel=ERROR" % user.key
            )
        if transport == "ssh":
            url = "ssh://%s@127.0.0.1:%d/%s" % (user.name, gerrit.ssh_port, project)
        else:
            url = "http://%s:%s@127.0.0.1:%d%s/a/%s" % (
                user.name,
                PASSWORD,
                gerrit.http_port,
                PREFIX,
                urllib.parse.quote(project, safe="/"),
            )
        self.git("init", "-q", "-b", "work")
        self.git("config", "user.name", user.name.capitalize())
        self.git("config", "user.email", user.name + "@example.com")
        self.git("config", "core.hooksPath", "/dev/null")
        self.git("remote", "add", "origin", url)
        self.git("fetch", "-q", "origin", "+refs/heads/*:refs/remotes/origin/*")

    def git(self, *args, check=True):
        return sh(["git", *args], env=self.env, cwd=self.dir, check=check)

    def commit(self, path, content, msg):
        full = os.path.join(self.dir, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(content)
        self.git("add", path)
        self.git("commit", "-q", "-m", msg)
        return self.git("rev-parse", "HEAD").stdout.strip()

    def push(self, *args, check=True):
        return self.git("push", *args, check=check)

    @staticmethod
    def plugin_lines(p):
        out = []
        for line in (p.stdout + p.stderr).splitlines():
            line = line.rstrip()
            if line.startswith("remote: branch-review: "):
                out.append(line[len("remote: branch-review: ") :])
        return out


CONFIG = """[branchReview]
  enabled = true
  targetBranch = refs/heads/master
  sourceBranchPattern = refs/heads/feature/*
  deleteSourceBranchOnSubmit = %s
"""


class BlackBox(unittest.TestCase):
    gerrit = None
    tmp = None
    alice = bob = None

    @classmethod
    def setUpClass(cls):
        g = cls.gerrit
        g.bootstrap_admin()
        keys = os.path.join(cls.tmp, "keys")
        os.makedirs(keys)
        cls.admin = User(g.admin_auth[0], None)
        cls.alice = g.create_user("alice", keys)
        cls.bob = g.create_user("bob", keys)
        _, grp = g.rest("PUT", "/groups/no-review", {"members": ["bob"]})
        cls.no_review_group = grp["id"]
        _, admins = g.rest("GET", "/groups/Administrators")
        cls.admins_group = admins["id"]

    def new_project(self, delete_on_submit=False, submit_type="REBASE_IF_NECESSARY"):
        name = "team/demo-" + uuid.uuid4().hex[:8]
        g = self.gerrit
        g.rest(
            "PUT",
            "/projects/" + urllib.parse.quote(name, safe=""),
            {"create_empty_commit": True, "submit_type": submit_type},
        )
        reg = "global:Registered-Users"
        g.rest(
            "POST",
            "/projects/%s/access" % urllib.parse.quote(name, safe=""),
            {
                "add": {
                    "refs/heads/feature/*": {
                        "permissions": {
                            "create": {"rules": {reg: {"action": "ALLOW"}}},
                            "push": {"rules": {reg: {"action": "ALLOW", "force": True}}},
                            "delete": {"rules": {reg: {"action": "ALLOW"}}},
                        }
                    },
                    "refs/meta/config": {
                        "permissions": {"push": {"rules": {self.admins_group: {"action": "ALLOW"}}}}
                    },
                    "refs/for/refs/heads/master": {
                        "permissions": {"push": {"rules": {self.no_review_group: {"action": "BLOCK"}}}}
                    },
                }
            },
        )
        admin = GitClient(g, self.admin, name, self.tmp, "http")
        admin.git("fetch", "-q", "origin", "refs/meta/config")
        admin.git("checkout", "-q", "FETCH_HEAD")
        admin.commit(
            "branch-review.config", CONFIG % ("true" if delete_on_submit else "false"), "Enable branch-review"
        )
        admin.push("origin", "HEAD:refs/meta/config")
        return name

    def changes(self, project):
        _, cs = self.gerrit.rest("GET", "/changes/?q=" + urllib.parse.quote("project:" + project))
        return cs

    def feature_client(self, project, user, transport):
        c = GitClient(self.gerrit, user, project, self.tmp, transport)
        c.git("checkout", "-q", "-b", "feature/x", "origin/master")
        return c

    def url(self, project, number):
        return "%sc/%s/+/%d" % (self.gerrit.canonical, project, number)

    def test_ssh_creation_and_http_update_report_real_url(self):
        p = self.new_project()
        c = self.feature_client(p, self.alice, "ssh")
        for i in range(3):
            c.commit("f%d.txt" % i, "content %d\n" % i, "commit %d" % i)
        out = c.push("-u", "origin", "feature/x")
        cs = self.changes(p)
        self.assertEqual(len(cs), 1)
        n = cs[0]["_number"]
        self.assertTrue(cs[0].get("work_in_progress"))
        self.assertEqual(
            GitClient.plugin_lines(out),
            ["refs/heads/feature/x: created Change %d (WIP)" % n, self.url(p, n)],
        )
        # The URL is real: it resolves on the server below the configured prefix.
        with urllib.request.urlopen(self.url(p, n).replace("localhost", "127.0.0.1"), timeout=30) as r:
            self.assertEqual(r.status, 200)

        h = GitClient(self.gerrit, self.alice, p, self.tmp, "http")
        h.git("checkout", "-q", "-b", "feature/x", "origin/feature/x")
        h.commit("g.txt", "more\n", "more")
        out = h.push("origin", "feature/x")
        self.assertEqual(
            GitClient.plugin_lines(out),
            ["refs/heads/feature/x: updated Change %d with patch set 2 (WIP)" % n, self.url(p, n)],
        )
        self.assertEqual(len(self.changes(p)), 1)

    def test_up_to_date_push_and_status_lookup(self):
        p = self.new_project()
        c = self.feature_client(p, self.alice, "ssh")
        c.commit("a.txt", "a\n", "a")
        c.push("origin", "feature/x")
        n = self.changes(p)[0]["_number"]
        again = c.push("origin", "feature/x")
        self.assertIn("Everything up-to-date", again.stderr)
        self.assertEqual(GitClient.plugin_lines(again), [])
        st = sh(
            [
                "ssh",
                "-F",
                "/dev/null",
                "-p",
                str(self.gerrit.ssh_port),
                "-i",
                self.alice.key,
                "-o",
                "IdentitiesOnly=yes",
                "-o",
                "StrictHostKeyChecking=no",
                "-o",
                "UserKnownHostsFile=/dev/null",
                "-o",
                "LogLevel=ERROR",
                "alice@127.0.0.1",
                "branch-review",
                "status",
                p,
                "feature/x",
            ]
        )
        info = json.loads(st.stdout)
        self.assertEqual(info["changeNumber"], n)
        self.assertTrue(info["inSync"])
        sync = sh(
            [
                "ssh",
                "-F",
                "/dev/null",
                "-p",
                str(self.gerrit.ssh_port),
                "-i",
                self.alice.key,
                "-o",
                "IdentitiesOnly=yes",
                "-o",
                "StrictHostKeyChecking=no",
                "-o",
                "UserKnownHostsFile=/dev/null",
                "-o",
                "LogLevel=ERROR",
                "alice@127.0.0.1",
                "branch-review",
                "sync",
                p,
                "feature/x",
            ]
        )
        self.assertIn("is up to date (patch set 1", sync.stdout)

    def test_source_push_allowed_review_denied(self):
        p = self.new_project()
        c = self.feature_client(p, self.bob, "ssh")
        sha = c.commit("b.txt", "b\n", "bob")
        out = c.push("origin", "feature/x")
        self.assertIn("feature/x", out.stderr)
        remote = c.git("ls-remote", "origin", "refs/heads/feature/x").stdout.split()[0]
        self.assertEqual(remote, sha)
        lines = GitClient.plugin_lines(out)
        self.assertIn("refs/heads/feature/x: branch update accepted but NOT synchronized to review", lines)
        self.assertTrue(any("not allowed to create a review" in line for line in lines), lines)
        self.assertEqual(self.changes(p), [])

    def test_multi_ref_push_over_http(self):
        p = self.new_project()
        c = self.feature_client(p, self.alice, "http")
        c.commit("one.txt", "1\n", "one")
        c.git("branch", "feature/y")
        c.commit("two.txt", "2\n", "two")
        out = c.push("origin", "feature/x", "feature/y")
        lines = GitClient.plugin_lines(out)
        self.assertEqual(len(self.changes(p)), 2)
        self.assertEqual(len([line for line in lines if ": created Change " in line]), 2)

    def test_submit_adds_one_commit_and_cleans_up(self):
        p = self.new_project(delete_on_submit=True)
        c = self.feature_client(p, self.alice, "ssh")
        before = c.git("rev-parse", "origin/master").stdout.strip()
        source = [c.commit("s%d.txt" % i, "%d\n" % i, "s%d" % i) for i in range(4)]
        c.push("origin", "feature/x")
        n = self.changes(p)[0]["_number"]
        g = self.gerrit
        g.rest("POST", "/changes/%d/ready" % n, {})
        g.rest("POST", "/changes/%d/revisions/current/review" % n, {"labels": {"Code-Review": 2}})
        g.rest("POST", "/changes/%d/revisions/current/submit" % n, {})
        deadline = time.time() + 60
        while time.time() < deadline:
            if c.git("ls-remote", "origin", "refs/heads/feature/x").stdout.strip() == "":
                break
            time.sleep(1)
        self.assertEqual(c.git("ls-remote", "origin", "refs/heads/feature/x").stdout.strip(), "")
        c.git("fetch", "-q", "origin", "+refs/heads/master:refs/remotes/origin/master")
        after = c.git("rev-parse", "origin/master").stdout.strip()
        self.assertEqual(c.git("rev-list", "--count", before + ".." + after).stdout.strip(), "1")
        parents = c.git("rev-list", "--parents", "-n", "1", after).stdout.split()
        self.assertEqual(parents, [after, before])
        for s in source:
            r = c.git("merge-base", "--is-ancestor", s, after, check=False)
            self.assertNotEqual(r.returncode, 0, "source commit %s became a target ancestor" % s)
        files = c.git("ls-tree", "--name-only", after).stdout.split()
        for i in range(4):
            self.assertIn("s%d.txt" % i, files)

    def test_ui_edit_reaches_branch(self):
        p = self.new_project()
        c = self.feature_client(p, self.alice, "ssh")
        c.commit("a.txt", "a\n", "a")
        c.push("origin", "feature/x")
        n = self.changes(p)[0]["_number"]
        before = c.git("rev-parse", "HEAD").stdout.strip()
        # Inline edit in Gerrit by alice (the change owner), then publish it.
        alice = ("alice", PASSWORD)
        self.gerrit.rest_raw("PUT", "/changes/%d/edit/a.txt" % n, b"edited in Gerrit\n", auth=alice)
        self.gerrit.rest("POST", "/changes/%d/edit:publish" % n, {}, auth=alice)
        deadline = time.time() + 60
        while True:
            remote = c.git("ls-remote", "origin", "refs/heads/feature/x").stdout.split()[0]
            if remote != before:
                break
            self.assertLess(time.time(), deadline, "branch was not updated with the edit")
            time.sleep(1)
        c.git("pull", "-q", "--ff-only", "origin", "feature/x")
        with open(os.path.join(c.dir, "a.txt")) as f:
            self.assertEqual(f.read(), "edited in Gerrit\n")
        log = c.git("log", "-1", "--format=%an|%s").stdout.strip()
        self.assertEqual(log, "Alice|Apply review edits from Change %d patch set 2" % n)
        c.commit("b.txt", "b\n", "b")
        out = c.push("origin", "feature/x")
        self.assertIn(
            "refs/heads/feature/x: updated Change %d with patch set 3 (WIP)" % n,
            GitClient.plugin_lines(out),
        )

    def test_z_restart_keeps_association(self):
        p = self.new_project()
        c = self.feature_client(p, self.alice, "ssh")
        c.commit("a.txt", "a\n", "a")
        c.push("origin", "feature/x")
        n = self.changes(p)[0]["_number"]
        # Also enable remote plugin administration for the reload test that runs next.
        sh(
            [
                "docker",
                "exec",
                self.gerrit.name,
                "git",
                "config",
                "-f",
                "/var/gerrit/etc/gerrit.config",
                "plugins.allowRemoteAdmin",
                "true",
            ]
        )
        self.gerrit.restart()
        c.commit("b.txt", "b\n", "b")
        out = c.push("origin", "feature/x")
        self.assertIn(
            "refs/heads/feature/x: updated Change %d with patch set 2 (WIP)" % n, GitClient.plugin_lines(out)
        )

    def test_zz_plugin_reload_keeps_working(self):
        # Remote plugin administration was enabled before the restart test (see test_z_...).
        p = self.new_project()
        c = self.feature_client(p, self.alice, "http")
        c.commit("a.txt", "a\n", "a")
        c.push("origin", "feature/x")
        n = self.changes(p)[0]["_number"]
        self.gerrit.rest("POST", "/plugins/branch-review/gerrit~reload", {})
        deadline = time.time() + 60
        while "Reloaded plugin branch-review" not in self.gerrit.logs():
            self.assertLess(time.time(), deadline, "plugin was not reloaded")
            time.sleep(1)
        c.commit("b.txt", "b\n", "b")
        out = c.push("origin", "feature/x")
        self.assertIn(
            "refs/heads/feature/x: updated Change %d with patch set 2 (WIP)" % n,
            GitClient.plugin_lines(out),
        )

    def test_plugin_identity(self):
        _, plugins = self.gerrit.rest("GET", "/plugins/")
        self.assertIn("branch-review", plugins)
        with open(os.path.join(HERE, "..", "version.txt")) as f:
            self.assertEqual(plugins["branch-review"]["version"], f.read().strip())


class JUnitResult(unittest.TextTestResult):
    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.records = []
        self._start = 0.0

    def startTest(self, test):
        self._start = time.time()
        super().startTest(test)

    def _rec(self, test, kind, text=""):
        self.records.append((test.id(), time.time() - self._start, kind, text))

    def addSuccess(self, test):
        super().addSuccess(test)
        self._rec(test, "ok")

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self._rec(test, "failure", self._exc_info_to_string(err, test))

    def addError(self, test, err):
        super().addError(test, err)
        self._rec(test, "error", self._exc_info_to_string(err, test))

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self._rec(test, "skipped", reason)


def write_junit(path, suite_name, records):
    ts = ET.Element(
        "testsuite",
        name=suite_name,
        tests=str(len(records)),
        failures=str(sum(r[2] == "failure" for r in records)),
        errors=str(sum(r[2] == "error" for r in records)),
        skipped=str(sum(r[2] == "skipped" for r in records)),
    )
    for tid, dur, kind, text in records:
        cls, _, name = tid.rpartition(".")
        tc = ET.SubElement(ts, "testcase", classname=cls, name=name, time="%.2f" % dur)
        if kind in ("failure", "error", "skipped"):
            ET.SubElement(tc, kind, message=text.splitlines()[-1] if text else kind).text = text
    ET.ElementTree(ts).write(path, encoding="utf-8", xml_declaration=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--series", required=True)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--keep", action="store_true", help="keep the container for debugging")
    ap.add_argument("-k", dest="pattern", help="only run tests matching this substring")
    args = ap.parse_args()

    with open(os.path.join(HERE, "..", "tools", "gerrit-releases.json")) as f:
        images = json.load(f)["series"]
    if args.series not in images:
        sys.exit("unknown series " + args.series)
    for tool in ("docker", "git", "ssh", "ssh-keygen"):
        if not shutil.which(tool):
            sys.exit("missing required tool: " + tool)
    jar = os.path.abspath(args.jar)
    if not os.path.isfile(jar):
        sys.exit("plugin JAR not found: " + jar)
    os.makedirs(args.out, exist_ok=True)

    gerrit = Gerrit(images[args.series]["image"], jar, args.out)
    tmp = tempfile.mkdtemp(prefix="branch-review-it-")
    print("starting %s (http %d, ssh %d)" % (gerrit.image, gerrit.http_port, gerrit.ssh_port), flush=True)
    ok = False
    try:
        gerrit.start()
        BlackBox.gerrit = gerrit
        BlackBox.tmp = tmp
        loader = unittest.TestLoader()
        if args.pattern:
            loader.testNamePatterns = ["*%s*" % args.pattern]
        suite = loader.loadTestsFromTestCase(BlackBox)
        runner = unittest.TextTestRunner(verbosity=2, resultclass=JUnitResult, stream=sys.stdout)
        result = runner.run(suite)
        write_junit(
            os.path.join(args.out, "TEST-blackbox-%s.xml" % args.series),
            "blackbox-gerrit-" + images[args.series]["version"],
            result.records,
        )
        ok = result.wasSuccessful()
    finally:
        gerrit.save_logs()
        if not args.keep:
            gerrit.remove()
        shutil.rmtree(tmp, ignore_errors=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
