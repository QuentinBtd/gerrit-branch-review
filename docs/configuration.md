# Configuration

## Project opt-in: `branch-review.config`

The plugin is active for a project only when `branch-review.config` exists at the root of the
project's own `refs/meta/config` and sets `enabled = true`. There is no inheritance: a file in
a parent project (including All-Projects) never enables a child, and server settings can only
restrict the plugin. Example: [`examples/branch-review.config`](../examples/branch-review.config).

```ini
[branchReview]
    enabled = true
    targetBranch = refs/heads/master
    sourceBranchPattern = refs/heads/feature/*
    sourceBranchPattern = refs/heads/bugfix/*
    excludeBranchPattern = refs/heads/feature/private/*
    deleteSourceBranchOnSubmit = false
    syncEditsToBranch = true
```

| Key | Required | Default | Meaning |
| --- | --- | --- | --- |
| `enabled` | no | `false` | Explicit opt-in. Missing file, missing key or `false` = disabled |
| `targetBranch` | when enabled | — | Fully qualified, existing branch (`refs/heads/master`, `refs/heads/main`, …). Fixed per lifecycle |
| `sourceBranchPattern` | when enabled, ≥1 | — | Source branches; repeat the key, patterns are ORed |
| `excludeBranchPattern` | no | none | Exclusions; they win over source patterns |
| `deleteSourceBranchOnSubmit` | no | `false` | Guarded cleanup after Submit (see [architecture](architecture.md#submit-and-cleanup)); grants no permission |
| `syncEditsToBranch` | no | `true` | Content edits made in Gerrit (inline edit, upload to the Change) are committed back onto the source branch (see [architecture](architecture.md#edits-made-in-gerrit)). `false` keeps the conservative pause |

Status of new Changes (WIP), WIP/Ready preservation and manual text preservation are fixed
behaviour, not options.

### Pattern grammar

Patterns are full ref names:

- exact: `refs/heads/release-notes` matches only that ref;
- prefix: a single trailing `*`, e.g. `refs/heads/feature/*`, matches any non-empty remainder
  including further `/` (`refs/heads/feature/a/b`). `refs/heads/feat*` matches
  `refs/heads/feature`.

Anything else is rejected: `*` elsewhere, `?`, character classes, braces, `^…` Gerrit ACL
regular expressions, patterns outside `refs/heads/`, invalid ref names, surrounding
whitespace. Regardless of patterns, these are never sources: the target branch, anything
outside `refs/heads/`, `refs/meta/*` (including plugin state), `refs/for/*`, `refs/changes/*`,
tags.

### Validation

Unknown sections, unknown keys, duplicated single-value keys, non-boolean booleans, invalid
patterns and a missing/invalid/non-existing target are errors. They are rejected:

- on direct pushes to `refs/meta/config` and uploads for review of `refs/meta/config`
  (`CommitValidationListener`, message names every problem);
- on Submit of a `refs/meta/config` Change (`MergeValidationListener`).

Patterns are validated even when `enabled = false`, so a file can be reviewed before enabling.
If an invalid file nevertheless exists (e.g. written before the plugin was installed), the
plugin performs no mutation for that project: pushes are accepted as usual and the output says
`branch-review.config is invalid; branch updates were accepted but NOT synchronized: …`.

### Editing the file

Requires the permissions Gerrit already demands for `refs/meta/config`: project owner plus
`Push` on `refs/meta/config` for a direct push, or `Push` on `refs/for/refs/meta/config` and
Submit rights for a review.

```sh
git fetch origin refs/meta/config && git checkout FETCH_HEAD
$EDITOR branch-review.config
git commit -am "Enable branch-review" && git push origin HEAD:refs/meta/config
# or for review: git push origin HEAD:refs/for/refs/meta/config
```

Effects of changes:

- enabling: affects subsequent pushes; existing branches are synchronized on their next push
  (or `branch-review sync`);
- changing `targetBranch`: active lifecycles are `BLOCKED` (never moved); see
  [architecture](architecture.md#lifecycle-states);
- changing patterns: branches that no longer match are reported as not synchronized; nothing
  moves and no second Change is created;
- disabling or removing the file: no synchronization, guard or cleanup; open Changes and
  branches are kept; pending recovery and cleanup work is not executed while disabled.

## Server settings (`gerrit.config`)

```ini
[plugin "branch-review"]
    disabled = false              # emergency switch for the whole server
    maxCommits = 2000             # contribution commits per synchronization (1..100000)
    pushWaitSeconds = 60          # how long a push waits for results (1..600)
    threads = 2                   # worker threads
    maxQueued = 200               # queued synchronizations before "server busy"
    recoveryIntervalSeconds = 300 # periodic recovery; 0 = only once after start
    maxAttempts = 5               # automatic retries per failure
```

None of these enables a project.

## Permissions (ACL examples)

The pusher needs normal branch permissions **and** normal review permissions:

```ini
[access "refs/heads/feature/*"]
    create = group Developers
    push = group Developers          # add +force (push = +force group …) for force pushes
[access "refs/for/refs/heads/master"]
    push = group Developers          # "Create Change": required to create the review Change
    addPatchSet = group Developers   # needed to update Changes owned by someone else
```

`delete` (or force push) on the source branch is needed by the *submitter* for the optional
cleanup. Forge Author/Committer are not needed for synthetic commits (they use the pusher's own
identity) but the site's usual rules still apply to the source commits pushed to the branch.
Other site policies (Signed-off-by required, commit validators, submit requirements, labels)
apply to synthetic commits and Changes unchanged.

## Status and repair

```sh
ssh -p 29418 user@host branch-review status <project> <branch>
ssh -p 29418 user@host branch-review sync [--accept-divergence|--new-lifecycle|--resume|--reset-text] <project> <branch>
curl -u user:pw 'https://host/a/projects/<project>/branch-review~status?ref=refs/heads/feature/x'
curl -u user:pw -X POST -H 'Content-Type: application/json' \
  -d '{"ref":"refs/heads/feature/x"}' 'https://host/a/projects/<project>/branch-review~sync'
```

`status` requires READ on the branch (otherwise "not found") and includes Change details only
if the caller can see the Change. `sync` requires push permission on the branch and then the
normal Change creation/patch set permissions; it is idempotent.
