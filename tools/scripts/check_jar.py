#!/usr/bin/env python3
"""Verifies a built plugin JAR: manifest identity, versions and bundled content."""

import sys
import zipfile

REQUIRED = {
    "Gerrit-PluginName": "branch-review",
    "Gerrit-ApiType": "plugin",
    "Gerrit-ReloadMode": "restart",
    "Gerrit-Module": "io.github.quentinbtd.gerrit.branchreview.Module",
    "Gerrit-SshModule": "io.github.quentinbtd.gerrit.branchreview.SshModule",
}
ALLOWED_PREFIXES = ("io/github/quentinbtd/gerrit/branchreview/", "META-INF/", "Documentation/")
ALLOWED_FILES = {
    "build-data.properties",
    "LICENSE",
    "io/",
    "io/github/",
    "io/github/quentinbtd/",
    "io/github/quentinbtd/gerrit/",
}


def main(jar, version, gerrit):
    errors = []
    with zipfile.ZipFile(jar) as z:
        manifest = {}
        for line in z.read("META-INF/MANIFEST.MF").decode().splitlines():
            if ": " in line:
                k, v = line.split(": ", 1)
                manifest[k] = v
        expected = dict(REQUIRED)
        expected["Implementation-Version"] = version
        expected["Gerrit-ApiVersion"] = gerrit
        for k, v in expected.items():
            if manifest.get(k) != v:
                errors.append("manifest %s=%r, expected %r" % (k, manifest.get(k), v))
        for name in z.namelist():
            if name in ALLOWED_FILES or name.startswith(ALLOWED_PREFIXES):
                continue
            errors.append("unexpected bundled entry: " + name)
        if "LICENSE" not in z.namelist():
            errors.append("LICENSE missing from JAR")
        if "build-data.properties" in z.namelist():
            data = z.read("build-data.properties").decode()
            if "build.timestamp.as.int=0" not in data:
                errors.append("build-data.properties is stamped (non-reproducible)")
    for e in errors:
        print("%s: %s" % (jar, e), file=sys.stderr)
    if not errors:
        print("%s: ok (branch-review %s for Gerrit %s)" % (jar, version, gerrit))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:4]))
