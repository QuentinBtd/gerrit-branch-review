# Architecture

branch-review mirrors ordinary branch pushes into one Gerrit Change per branch lifecycle. It
adds no client tooling and no external service: everything runs inside Gerrit through the
extension points listed in the [API investigation](api-investigation.md). Decisions are
recorded in [docs/adr](adr/).

## Components

| Component | Gerrit extension point | Responsibility |
| --- | --- | --- |
| `PushHook` | `ReceivePackInitializer`, `PostReceiveHook` | Picks eligible accepted branch updates, schedules their synchronization, prints one result per ref |
| `SyncExecutor` | `LifecycleListener`, `WorkQueue` | Bounded plugin thread pool; runs tasks in a request context of the acting account |
| `SyncService` | internal `BatchUpdate`, `ChangeInserter`, `PatchSetInserter` | Lifecycle state machine, squash planning, reservation, insertion, completion |
| `SquashPlanner` | JGit | Net contribution of a branch as a single tree on top of the target |
| `StateStore` | plain refs | Durable lifecycle and Change link records (compare-and-swap) |
| `ProjectConfigLoader`, `ConfigParser` | project state | Strict `branch-review.config` parsing, cached per config revision |
| `ConfigValidator`, `StateRefProtector` | `CommitValidationListener`, `RefOperationValidationListener` | Rejects invalid config and user writes to plugin state |
| `SubmitGuard` | `MergeValidationListener` | Submit type and staleness policy at Submit time |
| `MergeHandler` | `ChangeMergedListener` | Closes lifecycles, optional guarded source deletion |
| `RecoveryService` | `LifecycleListener` | Retries accepted-but-unsynchronized work and pending cleanups |
| `BranchStatus` (REST `branch-review~status` / `~sync`, SSH `branch-review status` / `sync`) | REST and SSH modules | Permission-filtered diagnosis and explicit, idempotent repair |

## Synchronization flow and transaction boundaries

```text
git push origin feature/x
  │
  ├─ Gerrit pre-receive (core): validation, ACLs, BatchUpdate ─► source ref committed ────┐ T1
  │                                                                                        │
  ├─ JGit status report sent ("ok refs/heads/feature/x")                                   │
  │                                                                                        │
  └─ post-receive (plugin, side-band still open)                                           │
       └─ SyncExecutor task as the pushing user, per-branch lock                           │
            1 read lifecycle state ref (CAS token)                                         │
            2 resolve any earlier reservation against NoteDb                               │
            3 read current source and target refs                                          │
            4 plan squash (JGit, in-core) and write tree + commit objects                 │
            5 reserve: link ref + lifecycle {pending: number, ps, commit} (CAS) ─────────── T2
            6 BatchUpdate as the user: patch-set ref + NoteDb + index + events ──────────── T3
            7 complete: lifecycle {synced, last source/target/commit} (CAS) ─────────────── T4
       └─ "remote: branch-review: …created Change N (WIP)" + URL
```

T1–T4 are separate atomic steps; nothing pretends they are one transaction:

| Crash or failure after | State left behind | What happens next |
| --- | --- | --- |
| T1 | Source updated, lifecycle unchanged (or `FAILED` recorded) | Push output says "accepted, NOT synchronized"/"FAILED"; the recovery service (transient failures) or the next push / `branch-review sync` synchronizes |
| T2 | Reservation for Change *N* / patch set *k* | Next run checks NoteDb: *N/k* absent → reservation discarded and work redone (no duplicate possible: *N* was never used) |
| T3 | Change/patch set exists, reservation still recorded | Next run finds *N/k* with the reserved commit → adopts it, no new Change or patch set |
| T4 | Consistent | — |

Delayed or out-of-order events are harmless: a task always synchronizes to the *current*
source ref value, so an older event becomes a no-op. Concurrent tasks for the same branch are
serialized by the lock; the state-ref compare-and-swap catches anything else and the task is
retried from fresh state. A user modifying the Change concurrently (new patch set, message
edit) makes the `BatchUpdate` fail its "current patch set unchanged" precondition and the task
is retried, so the edit is read and preserved.

Supported topology: one primary Gerrit server. Replicas only receive the replicated refs
(including `refs/meta/branch-review/*`); they must not ingest pushes for enabled projects.

## Lifecycle states

| Event | Behaviour |
| --- | --- |
| First non-empty push of an eligible branch | New lifecycle, new WIP Change targeting `targetBranch` |
| Branch updated, Change open | New patch set on the same Change after permission checks |
| Identical retry / equivalent content | No new patch set; association reported |
| Branch has no net change (initial) | No Change created |
| Branch has no net change (existing Change) | State `EMPTY`; current patch set is reported as not representing the branch and Submit is blocked |
| Conflict with target / unrelated history / over limit | State `CONFLICT`/`UNSUPPORTED`; Submit blocked; push explains |
| Change abandoned | Not restored. Restore it and push/sync again, or `sync --new-lifecycle` for a new Change |
| Change merged | Lifecycle closed; never receives patch sets again |
| Source deleted | `SOURCE_DELETED`; Change and history kept |
| Same name pushed again after deletion | Refused until the old Change is abandoned (then a new lifecycle) or `sync --resume` continues it |
| Rename | Deletion of the old name plus a new lifecycle for the new name |
| Reuse after Submit | Accepted only if the submitted commit is in the new history (branch recreated from or merged with the target); otherwise refused with instructions |
| `targetBranch` changed while a lifecycle is active | `BLOCKED`; the Change keeps its original target; abandon + `--new-lifecycle` or revert the config |
| Patterns changed so the branch no longer matches | Not synchronized, reported; nothing moves |
| Mapping inconsistent | `BLOCKED`; automatic mutation stops; diagnose with `status` |
| Patch set not produced by the plugin changes content | `DIVERGED`; paused until `sync --accept-divergence` |

## Generated text

The Change title is the synthetic commit's subject and the description is its body.

- One non-merge contribution commit: its subject and body (trailers removed).
- Otherwise: subject from the branch name (`refs/heads/` and the wildcard pattern's prefix
  removed, `-`/`_` to spaces, first letter capitalized, at most 72 characters); body
  `Squash of N commits from <ref>:` followed by `* <abbrev> <subject>` lines, oldest first,
  merges omitted (counted), at most 50 entries.
- Control and bidi-override characters are removed; other Unicode is kept.

Ownership is tracked separately for subject and body in the lifecycle state. A field stays
generator-owned while the published text equals the last generated text; as soon as it differs
(UI edit, `Edit commit message`), it becomes user-owned permanently, even if the text later
matches again. `sync --reset-text` (push permission required) returns both to the generator.
Trailers of the published message are preserved; `Change-Id` is always forced to the
lifecycle's synthetic key and placed last.

Published message edits and Gerrit rebases create patch sets that the plugin recognizes as
derived from its own (same tree and parent, or a clean rebase producing exactly the published
tree). Unpublished change edits are not touched; Gerrit keeps them on their base patch set.
WIP/Ready state, reviewers, votes (subject to Gerrit's copy rules), topic, hashtags, attention
set and privacy are never modified by synchronization.

## Submit and cleanup

See [ADR 0004](adr/0004-submit-policy.md) for the submit guard. After Gerrit reports the merge,
`MergeHandler` records the merged commit and the submitted source commit. If
`deleteSourceBranchOnSubmit = true` it deletes the source ref only when, at that time:

1. the project still enables the plugin and the option;
2. the ref is still an eligible source of this lifecycle (never the target or protected refs);
3. it still points at exactly the submitted source commit;
4. the submitting user holds `delete` (or force push) on it.

Deletion is a compare-and-swap on that commit as the server, recorded with the submitter's
account in the ref-updated event. Repeated merge events are idempotent; failures never affect
the Submit and are retried at most `maxAttempts` times by the recovery service. The cleanup
can therefore never exceed what the submitter could have done by hand.

## Configuration caching

Parsed configuration is cached by (project, `refs/meta/config` revision). Gerrit evicts the
project state on every `refs/meta/config` update, so the next lookup sees the new revision.
A push that updates `refs/meta/config` and source branches together does not synchronize the
branches (ambiguous configuration); the output says so and `branch-review sync` resolves it.

## Logs and metrics

Each synchronization logs one `INFO` line with project, ref, actor account id, trigger, result
and Change/patch set; cleanups log outcome and reason. Commit messages and tokens are not
logged. Metrics (`plugins/branch-review/...`):

| Metric | Type | Field |
| --- | --- | --- |
| `sync_results` | counter | `result`: created, updated, noop, not_synchronized, source_deleted, failed, in_progress |
| `cleanup_results` | counter | `result`: deleted, skipped, failed |
| `retry_backlog` | gauge | lifecycles awaiting retry or cleanup at the last recovery pass |

## Plugin reload and restart

The JAR declares `Gerrit-ReloadMode: restart`: on `gerrit plugin reload` the old instance is
stopped first (its executor drains for up to 30 s; interrupted work leaves a reservation that
is resolved later) and only then is the new instance started. Two instances therefore never
run concurrently with separate in-process locks. Pushes that land in the short window while
the plugin is not running are accepted without synchronization or output; push again or run
`branch-review sync`. After a server restart the recovery service resolves reservations and
retries failed synchronizations about 30 s after start.

## State backup, migration and removal

All state lives in the project repositories (`refs/meta/branch-review/*`) and NoteDb; back up
and restore repositories as usual. After restoring an older backup, run
`branch-review status` for affected branches: reservations are resolved automatically against
NoteDb on the next push, sync or recovery pass. The state format carries a `format` number for
future migrations.

Disabling (`enabled = false`, file removal, or `[plugin "branch-review"] disabled = true`)
stops all synchronization, guards and cleanups; Changes stay open and branches are kept.
Uninstalling the JAR has the same effect; the state refs remain and can be deleted by an
administrator with `git push origin :refs/meta/branch-review/...` only after uninstalling
(the plugin rejects such pushes while loaded).
