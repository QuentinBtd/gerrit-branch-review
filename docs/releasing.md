# Releasing

## Versioning policy

- One plugin SemVer stream, independent of Gerrit versions. Tags `vX.Y.Z`.
- First release: `0.1.0`.
- During `0.x`: `fix` → patch, `feat` → minor, breaking change → minor (called out under
  "⚠ BREAKING CHANGES"). From `1.0.0`: fix → patch, feat → minor, breaking → major.
- Public interface (changes are breaking unless backward compatible): the `branch-review.config`
  schema and defaults, server settings, REST endpoints and SSH commands with their options and
  output fields, push output lines, persisted state format and its migration, observable
  lifecycle/Submit behaviour, and the set of supported Gerrit series.
- The `-gerrit-<series>` asset suffix is a classifier, not a version. Adding a series is a
  `feat`; dropping one is a breaking change; updating a pinned Gerrit patch is a `fix(gerrit)`.

## Configuration

- [`release-please-config.json`](../release-please-config.json): `simple` strategy,
  `version.txt` + `CHANGELOG.md`, `bump-minor-pre-major: true`,
  `bump-patch-for-minor-pre-major: false`, `initial-version: 0.1.0`, draft releases with tags,
  release PR title `chore: release X.Y.Z`.
- [`.release-please-manifest.json`](../.release-please-manifest.json): release state. It is `{}`
  until the first release; Release Please then writes `{".": "0.1.0"}`.
- `version.txt`: the single version source (`0.0.0` before the first release). Bazel reads it
  into the JAR manifest and asset names.

The policy is tested against the pinned library:

```sh
node --test tools/release/version-policy.test.mjs
```

| Starting state | Input | Expected (tested) |
| --- | --- | --- |
| No release | any feat/fix | `0.1.0` |
| `0.1.0` | `fix:` | `0.1.1` |
| `0.1.0` | `feat:` | `0.2.0` |
| `0.1.0` | breaking | `0.2.0` with breaking notes |
| `1.0.0` | breaking | `2.0.0` |

## Commit conventions and squash merges

Commits follow Conventional Commits (checked by the `commit-msg` hook and in CI). Pull requests
are squash-merged; the PR title becomes the commit subject and is validated in CI. Put
`BREAKING CHANGE: …` in the PR description so it lands in the squash commit body (enable
"Default to pull request title and description" for squash merges in the repository settings).
The release PR's own title (`chore: release X.Y.Z`) is a valid Conventional Commit.

## Stable release flow

1. Merge changes into `main`. `release-please.yml` keeps a release PR up to date.
2. Review and merge the release PR. On that push Release Please creates the tag `vX.Y.Z` and a
   **draft** GitHub release.
3. The same workflow run calls `release.yml` with the release SHA: verify (version, manifest,
   changelog, tag ↔ SHA, formatting, lint, unit tests, build matrix), integration matrix on all
   series, build assets from that SHA, upload, then publish the draft. A release stays a draft
   until every asset is present.

Assets: `branch-review-X.Y.Z-gerrit-{3.12,3.13,3.14}.jar`, `branch-review-X.Y.Z.sha256`,
`branch-review-X.Y.Z-build-info.json` (source revision, Gerrit releases and tag commits,
toolchain), `branch-review-X.Y.Z-THIRD-PARTY.txt`. Each JAR is checked for manifest identity,
version, Gerrit API version, bundled content and unstamped build data.

Retrying: run the *Release* workflow manually with the same version and SHA. Identical assets
are skipped; a different asset with the same name makes the job fail instead of overwriting.

## Release candidates (optional, explicit)

Run the *Release* workflow manually with `version = X.Y.Z-rc.N` and the commit SHA (must be on
`main`). `X.Y.Z` must be greater than `version.txt` at that commit. The same verify, integration,
build and publish jobs run; the version is injected only into the build
(`BRANCH_REVIEW_VERSION`), the GitHub release is created as a **prerelease** on tag
`vX.Y.Z-rc.N`, and neither the manifest nor the changelog changes. In manifest mode Release
Please looks only for the tag of the manifest version, so RC tags never influence the next
stable version. Use increasing `N`; existing assets are never overwritten.

Not yet exercised against GitHub: the RC path was tested locally with `release:build` only.

## Local equivalents

```sh
export GITHUB_TOKEN=…   # read access, for release:plan only
mise run release:plan
mise run release:verify -- 0.1.0 <sha>     # checkout <sha> first; RELEASE_SKIP_MATRIX=1 skips the matrix
mise run release:build -- 0.1.0 <sha>      # assets in dist/release/0.1.0 from a clean worktree
GH_TOKEN=… mise run release:publish -- 0.1.0   # explicit, authenticated, idempotent
```

`verify`, `package` and `release:build` never push, tag or create releases.

## Reproducibility

JARs are built by Bazel's singlejar with fixed entry timestamps and unstamped build data. Two
consecutive `package` runs of the same revision produced identical SHA-256 checksums (see
[test evidence](test-evidence.md)). Builds on different OS/architectures were not compared
byte-for-byte; `build-info.json` records the toolchain for traceability.

## Repository setup required (once, by a maintainer)

- GitHub App for releases (contents: write, pull requests: write); store `RELEASE_APP_ID` as a
  repository variable and `RELEASE_APP_PRIVATE_KEY` as a secret, install it on this repository.
- Branch protection on `main`: require the CI checks (`Formatting, hooks and static checks`,
  `Conventional Commits (merge input)`, `Unit tests`, `Build / Gerrit 3.x`, `Integration /
  Acceptance + black-box (Gerrit 3.x)` for each series), require PRs, allow squash merge only.
- Actions settings: allow GitHub Actions to create pull requests is **not** needed (the App
  creates them). Restrict `GITHUB_TOKEN` default permissions to read.
