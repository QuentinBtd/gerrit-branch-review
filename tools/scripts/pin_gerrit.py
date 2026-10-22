#!/usr/bin/env python3
# Copyright (C) 2026 The branch-review Authors
# SPDX-License-Identifier: Apache-2.0
"""Recomputes the pins of tools/gerrit-releases.json from upstream.

For every series, the "version" field is authoritative (Renovate bumps it within the series).
This script refreshes the coupled values: the Gerrit tag commit, the SHA-256 of the plugin API
and acceptance framework JARs (downloaded and hashed, then cross-checked against Maven Central's
published .sha256), and the multi-arch image digest of gerritcodereview/gerrit:<version>-ubuntu24.

  pin_gerrit.py           update tools/gerrit-releases.json in place
  pin_gerrit.py --check   verify current pins against upstream, change nothing (exit 1 on drift)

BouncyCastle versions are not derived automatically: when a Gerrit update changes them, the
acceptance tests fail to start and the version must be updated from Gerrit's tools/deps files.
"""

import hashlib
import json
import pathlib
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
PINS = ROOT / "tools" / "gerrit-releases.json"
MAVEN = "https://repo1.maven.org/maven2/com/google/gerrit"


def fetch(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def jar_sha256(artifact, version):
    base = "%s/%s/%s/%s-%s.jar" % (MAVEN, artifact, version, artifact, version)
    digest = hashlib.sha256(fetch(base)).hexdigest()
    published = fetch(base + ".sha256").decode().split()[0].strip()
    if published != digest:
        raise SystemExit("checksum mismatch for %s %s: %s != %s" % (artifact, version, digest, published))
    return digest


def tag_commit(version):
    out = subprocess.run(
        [
            "git",
            "ls-remote",
            "https://gerrit.googlesource.com/gerrit",
            "refs/tags/v%s^{}" % version,
            "refs/tags/v%s" % version,
        ],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.split("\n")
    refs = dict(reversed(line.split("\t")) for line in out if line)
    sha = refs.get("refs/tags/v%s^{}" % version) or refs.get("refs/tags/v%s" % version)
    if not sha:
        raise SystemExit("no Gerrit tag v%s" % version)
    return sha


def image_digest(tag):
    token = json.loads(
        fetch(
            "https://auth.docker.io/token?service=registry.docker.io&scope=repository:gerritcodereview/gerrit:pull"
        )
    )["token"]
    req = urllib.request.Request(
        "https://registry-1.docker.io/v2/gerritcodereview/gerrit/manifests/" + tag,
        method="HEAD",
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.oci.image.index.v1+json, "
            "application/vnd.docker.distribution.manifest.list.v2+json",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.headers["Docker-Content-Digest"]


def main():
    check = "--check" in sys.argv[1:]
    data = json.loads(PINS.read_text())
    drift = []
    for series, r in data["series"].items():
        v = r["version"]
        if not v.startswith(series + "."):
            raise SystemExit("series %s pinned to %s: updates must stay within the series" % (series, v))
        want = {
            "tagCommit": tag_commit(v),
            "pluginApiSha256": jar_sha256("gerrit-plugin-api", v),
            "acceptanceFrameworkSha256": jar_sha256("gerrit-acceptance-framework", v),
            "image": "gerritcodereview/gerrit:%s-ubuntu24@%s" % (v, image_digest(v + "-ubuntu24")),
        }
        for k, val in want.items():
            if r.get(k) != val:
                drift.append("%s.%s: %s -> %s" % (series, k, r.get(k), val))
                r[k] = val
        if r["bouncycastle"] not in data["bouncycastle"]:
            raise SystemExit("series %s needs checksums for BouncyCastle %s" % (series, r["bouncycastle"]))
    for d in drift:
        print(d)
    if check:
        sys.exit(1 if drift else 0)
    PINS.write_text(json.dumps(data, indent=2) + "\n")


if __name__ == "__main__":
    main()
