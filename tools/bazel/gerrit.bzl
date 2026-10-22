"""Supported Gerrit series. Exact releases and checksums live in tools/gerrit-releases.json."""

load("@gerrit_releases//:releases.bzl", _BOUNCYCASTLE = "BOUNCYCASTLE", _GERRIT_RELEASES = "GERRIT_RELEASES")

# Adding or dropping a series is a deliberate compatibility decision (see docs/compatibility.md).
GERRIT_SERIES = ["3.12", "3.13", "3.14"]

GERRIT_RELEASES = _GERRIT_RELEASES

BOUNCYCASTLE = _BOUNCYCASTLE
