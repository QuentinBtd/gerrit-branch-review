# Configuration

## Project opt-in: `branch-review.config`

The plugin is active for a project when its **effective** configuration sets `enabled = true`.
The effective configuration merges the `branch-review.config` files at the root of the
`refs/meta/config` of the project and of its parents (see [Inheritance](#inheritance)), so a file
in a parent project enables all its descendants. Server settings can only restrict the plugin.
Example: [`examples/branch-review.config`](../examples/branch-review.config).

```ini
[branchReview]
    enabled = true
    targetBranch = refs/heads/master
    sourceBranchPattern = refs/heads/feature/*
    sourceBranchPattern = refs/heads/bugfix/*
    excludeBranchPattern = refs/heads/feature/private/*
    deleteSourceBranchOnSubmit = false
    syncEditsToBranch = true
    workInProgress = true
```

| Key | Required | Default | Meaning |
| --- | --- | --- | --- |
| `enabled` | no | `false` | Explicit opt-in. A missing file or key inherits the parent's value (`false` if no file in the chain sets it); `false` disables |
| `targetBranch` | with `sourceBranchPattern` | — | Target of the **default route**: fully qualified branch, expected to exist (`refs/heads/master`, `refs/heads/main`, …). Fixed per lifecycle |
| `sourceBranchPattern` | with `targetBranch` | — | Sources of the default route; repeat the key, patterns are ORed |
| `excludeBranchPattern` | no | none | Exclusions; they win over every route |
| `deleteSourceBranchOnSubmit` | no | `false` | Guarded cleanup after Submit (see [architecture](architecture.md#submit-and-cleanup)); grants no permission. Default for every route |
| `syncEditsToBranch` | no | `true` | Content edits made in Gerrit (inline edit, upload to the Change) are committed back onto the source branch (see [architecture](architecture.md#edits-made-in-gerrit)). `false` keeps the conservative pause. Default for every route |
| `workInProgress` | no | `true` | Whether a **new** Change starts Work in Progress; `false` creates it ready for review (reviewers and watchers are notified as for any new Change). Default for every route |

An enabled file needs at least one route: the default route (`targetBranch` with
`sourceBranchPattern`), one or more `[target]` sections, or both.

### Several target branches: `[target "<branch>"]`

Each `[target]` section routes source branches to its own target. Its name is the fully
qualified, existing target branch:

```ini
[branchReview]
    enabled = true
    # Default route: every eligible branch that no [target] section matches.
    targetBranch = refs/heads/master
    sourceBranchPattern = refs/heads/*
    excludeBranchPattern = refs/heads/private/*

[target "refs/heads/develop"]
    sourceBranchPattern = refs/heads/dev/*
    sourceBranchPattern = refs/heads/*/feat/*
    sourceBranchPattern = refs/heads/*/chore/*

[target "refs/heads/runtime"]
    sourceBranchPattern = refs/heads/run/*
    # Optional per-route overrides of the [branchReview] defaults.
    deleteSourceBranchOnSubmit = true
    syncEditsToBranch = false
```

| Pushed branch | Review target |
| --- | --- |
| `refs/heads/dev/login`, `refs/heads/alice/feat/search`, `refs/heads/team/chore/deps` | `refs/heads/develop` |
| `refs/heads/run/fix-42`, `refs/heads/run/feat/x` | `refs/heads/runtime` |
| `refs/heads/feature/x`, `refs/heads/a/b/feat/x` | `refs/heads/master` (default) |
| `refs/heads/private/x`, `refs/heads/develop`, `refs/heads/runtime`, `refs/heads/master` | none |

Keys of a `[target]` section: `sourceBranchPattern` (at least one), and optionally
`deleteSourceBranchOnSubmit`, `syncEditsToBranch` and `workInProgress`.

Routing rules:

1. Exclusions (`[branchReview] excludeBranchPattern`) win; target branches are never sources.
2. Among the `[target]` patterns matching a branch, the **most specific** wins, compared segment
   by segment from the left; at the first difference a literal segment beats `prefix*`, which
   beats a mid-path `*`, which beats a final `*`, and a longer `prefix*` beats a shorter one.
   `refs/heads/run/feat/x` goes to `runtime` because `run` (literal) beats `*`.
3. The default route applies only when no `[target]` pattern matches.

The same pattern in two `[target]` sections is rejected. **A target cannot have source
branches below it**: Git cannot store `refs/heads/dev` and `refs/heads/dev/x` at the same time,
so `[target "refs/heads/dev"]` with `sourceBranchPattern = refs/heads/dev/*` is rejected; use
`refs/heads/dev-*`, another prefix, or another target name.

The target of a review is fixed when its Change is created. If a configuration change routes an
active branch elsewhere, the lifecycle is `BLOCKED` (Submit too) until the previous routing is
restored, or the Change is abandoned and `branch-review sync --new-lifecycle` starts a new one.

`workInProgress` only sets the state of a new Change. Afterwards the WIP/Ready state chosen in
Gerrit is always preserved by synchronization, as is manually edited text; these are not
options.

### Inheritance

A project's effective configuration is built from the `branch-review.config` files of its
parent chain, merged from All-Projects down to the project. Projects without a file
contribute nothing.

- A key set in a lower file **replaces** the inherited value. This includes lists: a child that
  sets `sourceBranchPattern` replaces all of its parent's patterns.
- An empty value (`targetBranch =`, `excludeBranchPattern =`) clears the inherited value, for
  example to keep only `[target]` routes or to drop the inherited exclusions.
- `enabled = true` in a parent enables every descendant; a descendant opts out with
  `enabled = false` (and one of its own descendants can opt back in).
- `[target "<branch>"]` sections are merged by target branch, key by key: a child can override a
  single key of an inherited route (`workInProgress = false`) without repeating its patterns.
  `enabled = false` in a `[target]` section removes an inherited route.
- Each file must be valid on its own (syntax, keys, patterns). The rules that need the whole
  picture apply to the merged result: required keys, at least one route, overlapping routes,
  sources below a target.

Example: All-Projects (or any parent) enables the plugin for its descendants:

```ini
[branchReview]
    enabled = true
    targetBranch = refs/heads/master
    sourceBranchPattern = refs/heads/feature/*
```

A child project that uses `main` and opens Changes ready for review:

```ini
[branchReview]
    targetBranch = refs/heads/main
    workInProgress = false
```

Another child that opts out:

```ini
[branchReview]
    enabled = false
```

Errors from a file in a parent are prefixed with that project's name
(`in All-Projects: ...`). `GET .../branch-review~status` shows the effective configuration's
errors.

### Pattern grammar

Patterns are full ref names, matched segment by segment (`/`-separated):

- exact: `refs/heads/release-notes` matches only that ref;
- a `*` segment in the middle matches exactly one segment: `refs/heads/*/feat/*` matches
  `refs/heads/alice/feat/x` but not `refs/heads/a/b/feat/x` nor `refs/heads/feat/x`;
- a trailing `*` matches any non-empty remainder including further `/`:
  `refs/heads/feature/*` matches `refs/heads/feature/a/b`, `refs/heads/feat*` matches
  `refs/heads/feature`.

Anything else is rejected: `*` inside a segment (`fe*t/x`), `**`, `?`, character classes,
braces, `^…` Gerrit ACL regular expressions, patterns outside `refs/heads/`, invalid ref names,
surrounding whitespace. Regardless of patterns, these are never sources: any target branch, anything
outside `refs/heads/`, `refs/meta/*` (including plugin state), `refs/for/*`, `refs/changes/*`,
tags.

### Validation

Unknown sections, unknown keys, duplicated single-value keys, non-boolean booleans, invalid
patterns, a missing or invalid target, a `[target]` section without patterns, the same
pattern routed to two targets, and source patterns below a target branch are errors. A change
of `branch-review.config` is checked against the effective configuration it gives the project
(the file merged with the parents' current files, the parent being the one in the new
`project.config`). Errors are rejected:

- on direct pushes to `refs/meta/config` and uploads for review of `refs/meta/config`
  (`CommitValidationListener`, message names every problem);
- on Submit of a `refs/meta/config` Change (`MergeValidationListener`).

A target branch that does not exist in the project is only a **warning**: parent projects,
All-Projects included, usually have no branches of their own. In a project where a route's
target is missing, pushes routed to it are reported as not synchronized (`target branch ...
does not exist`) until the branch is created.

Changing a parent's file only checks the parent's own effective configuration; it can make a
descendant's merged configuration invalid (for example a child route that now repeats a
pattern of the parent). The descendant then behaves as with any invalid file (below) until one
of the files is fixed.

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
- disabling (`enabled = false`): no synchronization, guard or cleanup; open Changes and
  branches are kept; pending recovery and cleanup work is not executed while disabled. To
  disable a project, keep a file with `[branchReview] enabled = false`: **removing** the file
  (or a key) does not disable anything, it restores the values inherited from the parents,
  which may enable the plugin, or for example `deleteSourceBranchOnSubmit`, again.

## Server settings (`gerrit.config`)

```ini
[plugin "branch-review"]
    disabled = false              # emergency switch for the whole server
    maxCommits = 2000             # contribution commits per synchronization (1..100000)
    pushWaitSeconds = 60          # how long a push waits for results (1..600)
    threads = 2                   # worker threads
    maxQueued = 200               # queued tasks before "server busy"; recovery uses at most half
    recoveryIntervalSeconds = 300 # periodic recovery; 0 = only once after start; -1 = never
    maxAttempts = 5               # automatic retries per failure
    stopGraceSeconds = 30         # on stop/reload, time before running work is interrupted
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

`sync` waits up to `pushWaitSeconds` for the result and otherwise reports that the work is still
running; it is refused while the plugin is stopping or the queue is full. `status` requires READ on the branch (otherwise "not found") and includes Change details only
if the caller can see the Change. `sync` requires push permission on the branch and then the
normal Change creation/patch set permissions; it is idempotent.
