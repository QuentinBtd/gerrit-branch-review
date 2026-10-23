# branch-review

A Gerrit Code Review plugin that lets developers push ordinary feature branches and still get
Gerrit review, authorization and Submit. Each branch is mirrored into **one** Gerrit Change
(Work in Progress at first); every further push adds a synthetic squash patch set to that same
Change. Submit stays Gerrit's normal Submit and adds exactly one commit to the target branch.

```text
git push -u origin feature/my-feature
...
remote: branch-review: refs/heads/feature/my-feature: created Change 12345 (WIP)
remote: branch-review: https://review.example.org/c/my/project/+/12345
```

No client hook, push wrapper, external bot or `Change-Id` in source commits is needed. Projects
opt in explicitly; everything else in Gerrit behaves as before.

- Supported and tested: Gerrit **3.12.11**, **3.13.10**, **3.14.4** (one JAR per series, Java 21) —
  see [compatibility](docs/compatibility.md).
- License: [Apache-2.0](LICENSE).

## Install

Download the asset matching your Gerrit series from the GitHub release, verify it, and install
it under the plugin name `branch-review`:

```sh
sha256sum -c branch-review-0.1.0.sha256 --ignore-missing
cp branch-review-0.1.0-gerrit-3.14.jar $GERRIT_SITE/plugins/branch-review.jar
```

The manifest sets `Gerrit-PluginName: branch-review`, so the plugin name does not depend on
the file name; using `branch-review.jar` keeps upgrades simple (replace the file). Use the
`-gerrit-3.12`, `-gerrit-3.13` or `-gerrit-3.14` JAR for the matching server series only. The
plugin needs no database, no external service and no secrets. No release has been published
yet; build locally with `mise run build` (see [development](docs/development.md)).

## Enable for a project

Commit `branch-review.config` at the root of the project's `refs/meta/config`
([full reference](docs/configuration.md), [example](examples/branch-review.config)):

```ini
[branchReview]
    enabled = true
    targetBranch = refs/heads/master
    sourceBranchPattern = refs/heads/feature/*
    excludeBranchPattern = refs/heads/feature/private/*
    deleteSourceBranchOnSubmit = false
```

Several targets can be routed by pattern with `[target "<branch>"]` sections (e.g. `refs/heads/dev/*`
to `refs/heads/develop`); see [configuration](docs/configuration.md#several-target-branches-target-branch).

Developers need the usual branch permissions (`create`/`push` on the source branches) **and**
review permissions on the target (`push` on `refs/for/refs/heads/master`). Pushing a branch never
grants review rights.

## Workflow

1. `git push -u origin feature/x` — one WIP Change is created; the push prints its URL.
2. More commits, `git push` — the same Change gets a new patch set (its WIP/Ready state, reviewers,
   votes per Gerrit's copy rules, topic and hashtags are kept).
3. Mark the Change ready in Gerrit, review as usual. File edits made in Gerrit are committed back
   onto the branch (`git pull` before the next push). Edit the title/description in Gerrit if you
   like: edited parts are never overwritten again; untouched parts keep following the branch.
4. Submit normally. The target gains one commit with the branch's net changes; the branch's own
   commits do not become target ancestors. Optionally the source branch is deleted afterwards.

Patch sets are computed as a three-way merge of the branch onto the current target, so changes
made on the target meanwhile are kept. Conflicts, empty branches, unrelated histories and
incompatible submit types are reported and block Submit instead of producing wrong content.
`MERGE_ALWAYS` is not supported; `MERGE_IF_NECESSARY` works when the Change fast-forwards.

Check or repair a branch without pushing:

```sh
ssh -p 29418 review.example.org branch-review status my/project feature/x
ssh -p 29418 review.example.org branch-review sync my/project feature/x
```

## Documentation

- [Configuration and permissions](docs/configuration.md)
- [Architecture, lifecycles, failure handling](docs/architecture.md) and [ADRs](docs/adr/)
- [Security model](docs/security.md)
- [Compatibility](docs/compatibility.md)
- [API investigation and proof of concept](docs/api-investigation.md)
- [Development](docs/development.md), [releasing](docs/releasing.md), [dependency updates](docs/renovate.md)
- [Test evidence](docs/test-evidence.md)

The optional PolyGerrit source-branch link is **not implemented**; all functionality works
without any frontend change.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).
