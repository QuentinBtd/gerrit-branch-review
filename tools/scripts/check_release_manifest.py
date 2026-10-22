#!/usr/bin/env python3
"""Checks that the Release Please manifest, version.txt and the config agree."""

import json
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parents[2]
manifest = json.loads((root / ".release-please-manifest.json").read_text())
config = json.loads((root / "release-please-config.json").read_text())
version = (root / "version.txt").read_text().strip()

errors = []
if list(config.get("packages", {})) != ["."]:
    errors.append("release-please-config.json must define exactly the root package '.'")
pkg = config["packages"].get(".", {})
if pkg.get("release-as") and manifest.get("."):
    errors.append("remove the one-time release-as override after the first release")
if manifest:
    if manifest.get(".") != version:
        errors.append("manifest version %r != version.txt %r" % (manifest.get("."), version))
elif version != "0.0.0":
    errors.append("before the first release version.txt must be 0.0.0, got %r" % version)
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    errors.append("version.txt is not a release version: %r" % version)
for e in errors:
    print("error: " + e, file=sys.stderr)
sys.exit(1 if errors else 0)
