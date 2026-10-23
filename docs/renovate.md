# Dependency updates (self-hosted Renovate)

The project runs its own Renovate bot from [`.github/workflows/renovate.yml`](../.github/workflows/renovate.yml)
on a schedule (Mondays 03:23 UTC) and on demand. Dependabot is not used.

## Configuration split

- [`.renovaterc.jsonc`](../.renovaterc.jsonc): repository policy (managers, grouping, schedule,
  semantic commits, Gerrit series limits, post-upgrade task for Gerrit pins).
- [`.github/renovate/bot-config.json5`](../.github/renovate/bot-config.json5): bot-only/global
  options: platform, `autodiscover: false`, the single allowed repository, `allowedCommands`.
- Workflow credentials: the repository Actions secret `RENOVATE_TOKEN` (a dedicated
  fine-grained personal access token). Renovate detects the token owner's git author;
  no GitHub App author override is injected. Log level remains configurable.

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

1. Create or reuse a dedicated fine-grained personal access token with access to **only**
   `QuentinBtd/gerrit-branch-review`. Set an expiration and renew the secret before it expires.
   Repository permissions from Renovate's [GitHub platform documentation](https://docs.renovatebot.com/modules/platform/github/#permissions):
   - Contents: read/write.
   - Pull requests: read/write.
   - Issues: read/write (dependency dashboard).
   - Commit statuses: read/write.
   - Workflows: read/write (workflow dependency updates).
   - Dependabot alerts: read-only.
   Metadata read access is implicit; Administration is not required for this setup.
2. In this repository, open **Settings → Secrets and variables → Actions → Secrets → New
   repository secret**. Save the token as `RENOVATE_TOKEN`, not an Actions variable or an
   agent CLI credential. Do not commit or print its value. PRs and commits use the token
   owner's identity; the workflow no longer needs `RENOVATE_APP_CLIENT_ID`,
   `RENOVATE_APP_PRIVATE_KEY` or `RENOVATE_GIT_AUTHOR`. Existing settings are not deleted.
3. Merge the workflow migration to `main` before testing it. In **Actions → Renovate → Run
   workflow**, select `main` and enable **dry-run** for a lookup-only check. Rerunning an old
   App-based run does not load the new workflow. The workflow rejects an absent token before
   starting Renovate; a successful presence check alone does not prove valid authentication.
4. After a successful authenticated lookup, run without dry-run when ready. The normal bot
   run may create dependency branches, PRs and a dashboard issue; existing schedules, update
   age limits and manual-review/no-automerge policy still apply.

The built-in `GITHUB_TOKEN` used by Release Please is not a substitute for this setup:
Renovate must update workflow files and its generated PRs need to trigger CI. No workflow-file
manager or Gerrit re-pinning task is disabled to work around missing permissions.

## Local runs

```sh
mise run renovate:validate                    # offline, strict
bash tools/scripts/renovate.sh extract        # offline extraction report
RENOVATE_TOKEN=… mise run renovate:dry-run    # lookup only, writes nothing
RENOVATE_TOKEN=… mise run renovate:run        # real run, same configuration as the workflow
```
