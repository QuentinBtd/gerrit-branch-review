# ADR 0006: Release Please in manifest mode, assets from the exact tag, explicit RCs

- Status: accepted
- Date: 2026-10-22

## Context

Releases follow Conventional Commits and SemVer with a first release of exactly `0.1.0`.
Release Please creates release PRs and GitHub releases but does not build this plugin. Tags
and releases created with `GITHUB_TOKEN` do not trigger other workflows.

## Decision

- Release Please 17.11.2, manifest mode, `simple` strategy updating `version.txt` and
  `CHANGELOG.md`; `bump-minor-pre-major: true`, `bump-patch-for-minor-pre-major: false`.
- Bootstrap: empty manifest `{}` plus `initial-version: 0.1.0` and `version.txt = 0.0.0`. With
  no recorded release the library uses the initial version, so the first release is `0.1.0`
  regardless of the commit types; no `release-as` override exists to remove. Proven by
  `tools/release/version-policy.test.mjs`, which runs the pinned library with this config.
- Releases are created as drafts with tags (`draft` + `force-tag-creation`). The same workflow
  run (`release-please.yml`) calls `release.yml` with the release commit SHA, which verifies,
  runs the full integration matrix, builds from that SHA, uploads assets idempotently, and only
  then publishes the draft. No second event-triggered workflow is relied on.
- A GitHub App token is used for Release Please so the release PR triggers CI.
- Release candidates are an explicit `workflow_dispatch` of `release.yml` with `X.Y.Z-rc.N`.
  They are built with a version override, published as GitHub prereleases, and never touch
  the manifest or changelog. In manifest mode Release Please only looks for the tag of the
  manifest version, so RC tags cannot change stable version detection (verified in
  `manifest.js` of 17.11.2).

## Consequences

- `release:build` locally and in CI run the same script on the same revision and pins.
- A failed publication is retried by dispatching `release.yml` with the same version and SHA;
  identical assets are skipped, different ones fail.
- Squash merges: the PR title becomes the commit subject and is validated in CI.
