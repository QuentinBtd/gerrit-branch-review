# Development

## Prerequisites

Installed by you (not by mise):

- **mise** 2026.9.18 or newer (CI pins exactly 2026.9.18). Install with the
  [official instructions](https://mise.jdx.dev/getting-started.html) and make sure `mise` is on
  `PATH` for non-interactive shells too (Git hooks call `mise x`).
- **Git** ≥ 2.30, **OpenSSH** client (`ssh`, `ssh-keygen`).
- **Docker** (or a compatible engine with the `docker` CLI) for black-box tests, `dev:up` and the
  Renovate tasks. mise does not install or start a container engine.
- A C toolchain is not required. Allow ≥ 6 GiB RAM for Docker and ≥ 10 GiB free disk.

Platforms: Linux x86_64 (CI reference) and macOS arm64 (developed and tested). Linux arm64 has
lockfile entries but was not exercised. macOS x86_64 is not supported: the pinned
google-java-format has no native build for it. Windows is supported only through WSL2 with the
Linux instructions; native Windows is untested.

mise installs and pins everything else (`.mise.toml`, `mise.lock` with linux-x64, linux-arm64,
macos-x64 and macos-arm64 entries): Temurin JDK 21.0.12+8, Bazelisk 1.29.0 (Bazel 8.8.1 from
`.bazelversion`), prek, google-java-format, buildifier, shellcheck, shfmt, actionlint, taplo,
ruff, check-jsonschema, Python 3.13, Node 24 with release-please and markdownlint-cli2, and
`gh`. Renovate runs from its digest-pinned container image (the npm package is not installed
through mise because one of its transitive dependencies fails mise's provenance trust policy).

## Clean clone

```sh
git clone https://github.com/QuentinBtd/gerrit-branch-review.git && cd gerrit-branch-review
mise trust            # review .mise.toml first: it defines tools and tasks
mise install
mise run doctor
mise run hooks:install
mise run build        # dist/branch-review-<version>-gerrit-3.14.jar
mise run test
mise run test:integration
```

## Tasks

| Task | What it does |
| --- | --- |
| `doctor` | Reports tool versions, host prerequisites, Docker, disk |
| `setup` | `doctor` + pre-fetches checksum-verified Gerrit artifacts for all series |
| `hooks:install` / `hooks:run` | Installs prek hooks (pre-commit, commit-msg) / runs them on all files |
| `fmt` / `fmt:check` | Formats Java, Starlark, shell, Python, TOML / checks only |
| `lint` | buildifier lint, shellcheck, ruff, actionlint, config schemas, markdownlint |
| `build [SERIES]` | Builds one artifact into `dist/` (default 3.14) |
| `build:matrix` | Builds 3.12, 3.13, 3.14 |
| `test` | Unit tests |
| `test:integration [SERIES]` | In-process acceptance tests + black-box tests (Docker) |
| `test:matrix` | Unit + acceptance + black-box for every series |
| `verify` | Local CI equivalent (format, hooks, lint, unit, build matrix); `mise run verify -- --full` adds the integration matrix |
| `dev:up [SERIES]` / `dev:down` / `dev:logs` | Disposable Gerrit on `127.0.0.1:8080`/`29418` with the plugin |
| `package` | All JARs + `.sha256`, build info, third-party inventory in `dist/` |
| `release:plan` / `release:verify` / `release:build` / `release:publish` | See [releasing](releasing.md) |
| `renovate:validate` / `renovate:dry-run` / `renovate:run` | See [renovate](renovate.md) |
| `clean` | Removes `dist/`, `build/` and Bazel symlinks (`-- --all` also expunges Bazel's output base) |

Arguments go after the task name, e.g. `mise run build 3.12`, `mise run test:integration 3.13`.
`bash tools/scripts/test.sh acceptance 3.12` and `... blackbox 3.12` run the two halves separately.

## Layout

```text
src/main/java/…/branchreview   plugin sources (config, squash, text, state, sync, submit, api, ssh)
src/test/java/…/unit           pure unit tests
src/test/java/…/it             in-process acceptance tests (LightweightPluginDaemonTest)
integration/blackbox.py        container + real git over SSH/HTTP against the packaged JAR
tools/gerrit-releases.json     exact Gerrit releases, checksums, image digests
tools/bazel/                   Bazel macros and module extensions
tools/scripts/                 task implementations shared by mise and CI
tools/release/                 Release Please policy fixtures
```

## Two kinds of integration tests

- **Acceptance tests** (`*IT`) start an embedded Gerrit of the selected series in the test JVM
  and push through Gerrit's in-process transport, which runs the same `AsyncReceiveCommits` and
  post-receive chain as SSH/HTTP. They give fast, deterministic coverage of permissions,
  lifecycles, Submit strategies, failure injection and concurrency.
- **Black-box tests** run the official `gerritcodereview/gerrit` image (pinned digest), install
  the packaged JAR as an operator would, and use the real `git` CLI over SSH and smart HTTP with
  disposable users, keys and projects. They prove manifest/loading, transport output timing,
  canonical URLs below a path prefix, restart persistence and end-to-end Submit/cleanup.
  Containers bind to 127.0.0.1 only and are removed afterwards (`--keep` for debugging; label
  `io.github.quentinbtd.branch-review.it`).

Reports: `build/test-results/<series>/` (JUnit XML, black-box `gerrit.log`).

## Troubleshooting

- `mise x` not found from a Git hook: install mise system-wide or add it to the PATH your Git
  uses; hooks never activate a shell.
- Bazel `lockfile` errors after editing `MODULE.bazel`: `bazelisk mod deps --lockfile_mode=update`.
- Black-box tests slow on SSH: they pass `-F /dev/null`, so personal SSH configuration is ignored;
  check Docker networking instead.
- Port in use for `dev:up`: set `BRANCH_REVIEW_DEV_HTTP` / `BRANCH_REVIEW_DEV_SSH`.
