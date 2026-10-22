# ADR 0005: Standalone Bazel build, one artifact per Gerrit series

- Status: accepted
- Date: 2026-10-22

## Context

The plugin targets Gerrit 3.12, 3.13 and 3.14 (reference 3.14). It uses internal server
classes whose source signatures are stable across these releases, but bytecode-level
compatibility of a single JAR was not something to assume. Two build arrangements were
possible: in-tree builds against prepared Gerrit checkouts, or a standalone build against the
published API artifacts.

## Decision

- Standalone Bazel (bzlmod) build, Bazel pinned by `.bazelversion` (8.8.1) via Bazelisk. The
  Gerrit `gerrit-plugin-api` (neverlink) and `gerrit-acceptance-framework` (tests) JARs of each
  exact release, plus the BouncyCastle version each WAR ships, are declared from
  `tools/gerrit-releases.json` with SHA-256 checksums by a module extension.
- `--//:gerrit=3.12|3.13|3.14` (`--config=gerrit-X`) selects the series; the same sources are
  compiled for each.
- Java 21 language level and runtime (`remotejdk_21`), matching every supported Gerrit; local
  tools use Temurin 21.0.12+8 from mise.
- Release assets: `branch-review-<version>-gerrit-<series>.jar`, one per series, all carrying
  `Gerrit-PluginName: branch-review`, `Implementation-Version: <version>` and
  `Gerrit-ApiVersion: <exact Gerrit release>`. The series suffix is a classifier, not a version.

An in-tree build was not needed: the acceptance framework from Maven supports full in-process
server tests, and black-box tests load the packaged JAR into the official container image.

## Consequences

- No Gerrit checkout, Node or Python dependency is needed to build the plugin.
- Each artifact is tested on its own series; no cross-series loading is claimed.
- A new Gerrit patch release is a pin update re-verified by `pin_gerrit.py`; a new series is a
  deliberate change to `GERRIT_SERIES`, the matrices and the compatibility table.
