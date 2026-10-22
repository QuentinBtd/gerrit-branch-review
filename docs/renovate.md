# Dependency updates (self-hosted Renovate)

The project runs its own Renovate bot from [`.github/workflows/renovate.yml`](../.github/workflows/renovate.yml)
on a schedule (Mondays 03:23 UTC) and on demand. Dependabot is not used.

## Configuration split

- [`.renovaterc.jsonc`](../.renovaterc.jsonc): repository policy (managers, grouping, schedule,
  semantic commits, Gerrit series limits, post-upgrade task for Gerrit pins).
- [`.github/renovate/bot-config.json5`](../.github/renovate/bot-config.json5): bot-only/global
  options: platform, `autodiscover: false`, the single allowed repository, `allowedCommands`.
- Workflow environment: credentials (`RENOVATE_TOKEN` from a GitHub App token), log level,
  git author.

Both the GitHub Action wrapper (full commit SHA) and the bot image
(`ghcr.io/renovatebot/renovate:44.145.1-full@sha256:…`) are pinned; local tasks use the same
image.

## What is tracked

| Class | Manager | Notes |
| --- | --- | --- |
| mise tools (`.mise.toml`) and `mise.lock` | `mise` | Renovate runs `mise lock` itself for the lock file |
| mise version for CI (`jdx/mise-action` input) and `min_version` | `github-actions` + regex | grouped as "mise" |
| GitHub Actions | `github-actions` | full SHA pins with version comments |
| Bazel modules | `bazel-module` | |
| Bazel version (`.bazelversion`) | `bazelisk` | kept below 9 |
| Gerrit releases (`tools/gerrit-releases.json`) | regex (Maven) | one PR per series, never leaves its series; `pin_gerrit.py` recomputes tag commit, JAR checksums and image digest in the same branch (allowlisted command) |
| Renovate image in the local task | regex (Docker) | |
| prek hooks | `pre-commit` | enabled explicitly (opt-in manager); built-in hooks have no revision, remote hook repos added later are covered |

Extraction from the current tree (offline, no credentials): `mise run renovate:extract` is
exposed as `bash tools/scripts/renovate.sh extract`; it found 59 dependencies across those
managers (see [test evidence](test-evidence.md)). GitHub-hosted datasources need a token for
lookups; they are marked "github-token-required" in the offline extraction only.

## Policy

- `minimumReleaseAge: 14 days`, `prConcurrentLimit: 6`, `prHourlyLimit: 2`, dependency dashboard.
- No automerge. Major updates require dashboard approval.
- Commit messages: `chore(deps)`, `ci(deps)`, `build(deps)`, `fix(gerrit)` — compatible with
  Release Please (`fix(gerrit)` appears in the changelog because it changes the tested Gerrit
  release).
- Gerrit: `allowedVersions` per series (`/^3\.14\.\d+$/` etc.). Adding/removing a series is a
  manual, reviewed change.
- Post-upgrade commands are disabled except the single allowlisted, reviewed
  `python3 -I tools/scripts/pin_gerrit.py`, restricted to `tools/gerrit-releases.json`.
- The bot runs only from scheduled/manual runs on `main` (trusted code); fork PRs never get its
  credentials.

## Setup (once, by a maintainer)

1. Create a GitHub App with repository permissions: Contents read/write, Pull requests
   read/write, Issues read/write (dependency dashboard), Workflows read/write (to update workflow
   pins), Checks read, Metadata read. Install it on this repository only.
2. Repository variable `RENOVATE_APP_ID`, secret `RENOVATE_APP_PRIVATE_KEY`, variable
   `RENOVATE_GIT_AUTHOR` (`<app-slug>[bot] <<app-user-id>+<app-slug>[bot]@users.noreply.github.com>`).
3. Run the workflow manually with "dry run" first.

A fine-grained personal access token with the same repository permissions can replace the App
token (`RENOVATE_TOKEN`), at the cost of attributing PRs to that user. The default
`GITHUB_TOKEN` is not sufficient: PRs it creates do not trigger CI and it cannot update
workflow files.

## Local runs

```sh
mise run renovate:validate                    # offline, strict
bash tools/scripts/renovate.sh extract        # offline extraction report
RENOVATE_TOKEN=… mise run renovate:dry-run    # lookup only, writes nothing
RENOVATE_TOKEN=… mise run renovate:run        # real run, same configuration as the workflow
```
