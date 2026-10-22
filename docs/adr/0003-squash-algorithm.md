# ADR 0003: Synthetic squash commit by three-way merge onto the captured target tip

- Status: accepted
- Date: 2026-10-22

## Context

Each patch set must be a single-parent commit with the branch's complete net contribution,
must not import the source's commit chain, and must never drop unrelated target changes.
Two candidates were evaluated:

1. Source tree with the merge base as parent. The tree is exactly the branch, but the parent
   is old; Submit must rebase (or merge), and under `MERGE_IF_NECESSARY` it would create a merge
   commit. Reviewers also see a diff against an outdated base.
2. Three-way merge of the source into the current target tip, result committed with the target
   tip as the only parent.

Placing the source tree unchanged on a newer target tip (a naive "squash") would revert every
target change made after the branch point; it was rejected.

## Decision

Option 2. For captured source `S` and target `T`:

- Contribution commits: `rev-list T..S`, bounded by `maxCommits` (counted without buffering
  before sorting).
- Merge bases: JGit `RevFilter.MERGE_BASE`. None → `UNRELATED` (rejected).
- Tree: JGit `RECURSIVE` merger (in-core), which handles multiple merge bases by building a
  virtual base. Conflicts → `CONFLICT` with the paths; no tree with conflict markers is ever
  produced.
- Result tree equal to `T`'s tree → `EMPTY`.
- Commit: tree = merged tree, single parent = `T`, author = committer = pushing user's identity
  with the source tip's committer timestamp and zone (deterministic: the same source state,
  target and text produce the same commit id), message from [text ownership](../architecture.md#generated-text).

Renames, deletions, binary blobs, file modes, symlinks and gitlinks are handled by JGit's
merger (unit tests cover modes, symlinks, gitlinks and binary content).

## Consequences

- Target-only work is preserved (regression tests in `SquashPlannerTest` and `SubmitIT`).
- Merging the target into the source is supported: the merge base moves forward and only the
  net contribution remains.
- Force pushes and rebases simply produce a new net contribution.
- Original commit signatures are not carried over; the synthetic commit is new. Traceability:
  the generated body lists abbreviated source commit ids, and every patch set description
  records `<sourceRef> @ <source commit>` and the target commit it was squashed onto.
