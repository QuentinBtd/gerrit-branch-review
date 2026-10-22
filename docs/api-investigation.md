# Gerrit API investigation

This document records what was examined in the actual Gerrit sources of the supported
releases before the architecture was fixed, what the proof of concept demonstrated, and why
each integration point was chosen. Online Gerrit documentation describes the development
branch; every statement below was checked against the tagged sources listed here.

## Examined releases

| Series | Tag | Commit | JGit submodule | Java (from `.bazelrc`) | Gerrit's own Bazel (`.bazelversion`) |
| --- | --- | --- | --- | --- | --- |
| 3.12 | [`v3.12.11`](https://gerrit.googlesource.com/gerrit/+/refs/tags/v3.12.11) | `748845aa72968f6a851ee250fcbdd191ca99f857` | `f9d6f9c7ce6c0fd799f9f288961c1db1e45fc129` (v7.5.0.202512021534-r-59) | 21 (`--java_language_version=21`, `remotejdk_21`) | 7.7.1 |
| 3.13 | [`v3.13.10`](https://gerrit.googlesource.com/gerrit/+/refs/tags/v3.13.10) | `f9e70cda0327381db8d739f017ccb05dd90ee681` | `f9d6f9c7ce6c0fd799f9f288961c1db1e45fc129` (same as 3.12.11) | 21 | 7.7.1 |
| 3.14 | [`v3.14.4`](https://gerrit.googlesource.com/gerrit/+/refs/tags/v3.14.4) | `6d038645483357e8f72df648dcb2c4d7aa1914c9` | `182a140566592276ac94146b26cd43242b03965c` (v7.7.1.202607240634-r-33) | 21 | 8.6.0 |

Artifacts used by this project (all from Maven Central, checksums pinned in
[`tools/gerrit-releases.json`](../tools/gerrit-releases.json) and re-verifiable with
`python3 tools/scripts/pin_gerrit.py --check`): `com.google.gerrit:gerrit-plugin-api` and
`com.google.gerrit:gerrit-acceptance-framework` at the exact versions above. Both are
self-contained ("fat") JARs; BouncyCastle is the only test-runtime dependency they do not
bundle (1.80 for 3.12/3.13 per `tools/deps.bzl`, 1.84 for 3.14 per `tools/deps.toml`).
Container images for black-box tests: `gerritcodereview/gerrit:<version>-ubuntu24` pinned by
multi-arch digest (amd64 and arm64 available for all three).

Release selection: the newest patch of each series that was at least two weeks old when the
work started (3.12.11, 3.13.10 and 3.14.4 were published 2026-09-23). 3.14.4 is the reference.

## Capability table

"Same" means the file is byte-identical (after the license header) to 3.14.4, or the relevant
signatures are unchanged. All listed interfaces are invoked in-process by Gerrit core.

| Capability | API (class / method) | Kind | Registration | Call timing and thread | User context | Transaction behaviour | 3.12.11 | 3.13.10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Observe accepted pushes | `org.eclipse.jgit.transport.PostReceiveHook#onPostReceive(ReceivePack, Collection<ReceiveCommand>)` | JGit interface, Gerrit `DynamicSet<PostReceiveHook>` extension point (`GerritGlobalModule`) | `DynamicSet.bind(binder(), PostReceiveHook.class)` | After Gerrit's pre-receive processing applied all ref updates and after JGit sent the status report, **before** the side-band stream is closed (`ReceivePack.service()` → `PostReceiveExecutor.close()`); SSH command thread or HTTP servlet thread | Request scope of the pushing user (`Provider<CurrentUser>`) | Ref updates already committed; hook cannot change results. Only `Result.OK` commands are passed | Same (`LazyPostReceiveHookChain` identical) | Same |
| Know the project of a receive | `com.google.gerrit.server.git.ReceivePackInitializer#init(Project.NameKey, ReceivePack)` | Gerrit extension point | `DynamicSet.bind(..., ReceivePackInitializer.class)` | When `ReceiveCommits` is constructed, same `ReceivePack` instance as the post-receive call | Request scope | none | Same | Same |
| Push output | `ReceivePack#sendMessage(String)` | JGit | — | Writes to `msgOut` (side band 2); during post-receive the band is still open | — | — | Same JGit `service()` structure (verified in both JGit revisions) | Same |
| Pre-receive replacement | `AsyncReceiveCommits.asHook()` sets Gerrit's own `PreReceiveHook`; post-receive is `LazyPostReceiveHookChain` | Internal | Not replaceable by plugins | — | — | Gerrit processes every command in a `BatchUpdate` inside its pre-receive hook | Same | Same |
| Ref-updated events | `GitReferenceUpdatedListener` | Extension point | `DynamicSet` | Asynchronous with respect to the Git protocol response (fired from `BatchUpdate` post-update / event executor) | Event carries an account, no request scope | After commit | Present | Present |
| Create a Change | `ChangeInserter.Factory#create(Change.Id, ObjectId, String refName)` + `BatchUpdate#insertChange` | Internal server API (`com.google.gerrit.server.change`) | Guice injection | Caller thread | Explicit `CurrentUser` given to `BatchUpdate.Factory#create` | One `BatchUpdate`: patch-set ref + NoteDb meta ref updated together; index and events in post-update | Same signatures (3.14 adds `@SendEmailEnabled`, internal) | Same |
| Add a patch set | `PatchSetInserter.Factory#create(ChangeNotes, PatchSet.Id, ObjectId)` | Internal | Injection | Caller thread | BatchUpdate user | Same as above; checks `ChangePermission.ADD_PATCH_SET` itself | Same | Same |
| Reserve a Change number | `com.google.gerrit.server.Sequences#nextChangeId()` | Internal | Injection | — | — | Durable sequence in `refs/sequences/changes` | Same | Same |
| Commit validation for inserted commits | `ChangeInserter`/`PatchSetInserter` run `CommitValidators.forGerritCommits` (author/committer identity, Change-Id, plugin `CommitValidationListener`s) | Internal | — | Inside `BatchUpdate.updateRepo` | BatchUpdate user | Failure aborts the whole batch | Same | Same |
| Permissions | `PermissionBackend.user(u).project(p).ref(r).check(RefPermission.CREATE_CHANGE / READ / UPDATE / DELETE)`, `.change(notes).check(ChangePermission.READ / ADD_PATCH_SET)` | Stable server API | Injection | — | Explicit | — | `RefPermission` implements `RefPermissionOrLabel` (no effect on callers) | Same |
| Plugin project config file | Raw blob `branch-review.config` read at `ProjectState.getConfig().getRevision()`; `PluginConfigFactory.getProjectPluginConfig` also exists | Stable | — | — | — | Revision changes on every `refs/meta/config` update; `ProjectCache` is evicted on such updates | Same | Same |
| Validate config pushes | `CommitValidationListener#onCommitReceived(CommitReceivedEvent)` (`event.refName` is the destination, also for `refs/for/refs/meta/config`) | Extension point | `DynamicSet` | During receive validation, before the update | Pusher | Rejection rejects the command | Same | Same |
| Validate ref operations | `RefOperationValidationListener#onRefOperation(RefReceivedEvent)` | Extension point | `DynamicSet` | Receive (create/update/delete) and REST branch APIs | Caller | Rejection | Same | Same |
| Submit-time checks | `MergeValidationListener#onPreMerge(Repository, CodeReviewRevWalk, CodeReviewCommit, ProjectState, BranchNameKey, PatchSet.Id, IdentifiedUser)` | Extension point | `DynamicSet` | `MergeOp`, per change, before the submit strategy runs; a `MergeValidationException` makes Submit fail with its message | Submitter | The submit's ref update is a CAS; `MergeOp` retries re-run the listener | Same (identical file) | Same |
| Effective submit type | `ChangeData#submitTypeRecord()` (honours project config and Prolog/submit rules) | Internal | — | — | — | — | Same | Same |
| Post-merge | `ChangeMergedListener#onChangeMerged(Event)` (`getNewRevisionId()` is the merged revision for rebase/cherry-pick) | Extension point | `DynamicSet` | After the merge is committed | `event.getWho()` = submitter | — | Same | Same |
| Ref update context (test enforcement) | `RefUpdateContext.open(RefUpdateType.PLUGIN / CHANGE_MODIFICATION / BRANCH_MODIFICATION)` | Internal | — | Must wrap direct ref updates; acceptance tests fail otherwise | — | — | Same enum values | Same |
| Background work | `WorkQueue#createQueue(int, String)`, `OneOffRequestContext#openAs(Account.Id)`, `LifecycleListener` | Stable | `LifecycleModule.listener()` | Plugin-owned executor, shut down on unload | Re-opened per task | — | Same | Same |
| REST / SSH | `RestApiModule` (`PROJECT_KIND` views), `PluginCommandModule` + `SshCommand` | Plugin API | Manifest `Gerrit-Module`, `Gerrit-SshModule` (requires `Gerrit-ApiType: plugin`) | — | Caller | — | Same | Same |
| Custom keyed values on Changes | `ChangeApi.setCustomKeyedValues` | Public API | — | — | — | Stored in NoteDb, editable through REST | Present | Present |

Public plugin extension points used: `PostReceiveHook` and `ReceivePackInitializer` sets,
`CommitValidationListener`, `RefOperationValidationListener`, `MergeValidationListener`,
`ChangeMergedListener`, `LifecycleListener`, REST and SSH modules. Version-sensitive internals
used: `ChangeInserter`, `PatchSetInserter`, `BatchUpdate`, `Sequences`, `ChangeNotes`,
`ChangeData`, `RefUpdateContext`, `GitReferenceUpdated`. They compile unchanged against all
three series; this is why the project builds one artifact per series rather than assuming
binary compatibility (see [ADR 0005](adr/0005-build-and-compatibility.md)).

## Findings that shaped the design

1. **Synchronous URL delivery is possible without core changes.** JGit 7.5/7.7
   `ReceivePack.service()` wraps the processing in a try-with-resources `PostReceiveExecutor`
   whose `close()` invokes the post-receive hook *after* `sendStatusReport` and before
   `close()` flushes and terminates the side-band. Gerrit installs `LazyPostReceiveHookChain`,
   which runs every plugin-registered `PostReceiveHook`. Messages sent from there appear as
   `remote:` lines in the `git push` output. Proven for SSH and smart HTTP in the black-box
   tests on all three releases (see [test evidence](test-evidence.md)).
2. **Ref-updated events alone are not a response channel.** `GitReferenceUpdatedListener`
   is fired after the batch commits and without access to the `ReceivePack`; it cannot write
   to the client. It is not used for synchronization.
3. **Gerrit owns pre-receive.** `AsyncReceiveCommits` sets its own pre-receive hook and
   applies every command itself. The plugin does not replace or wrap it; it never changes
   command results, so normal receive behaviour (validation, autoclose, magic branches,
   atomic pushes, ACLs) is untouched.
4. **Insertion APIs do not check creation permission.** `ChangeInserter` validates commits but
   does not check `CREATE_CHANGE` (the REST `CreateChange` does). The plugin checks `READ` and
   `CREATE_CHANGE` on the target explicitly before reserving anything; `PatchSetInserter`
   checks `ADD_PATCH_SET` itself and the plugin checks `READ` first for a clean message.
5. **Commit validators apply to synthetic commits.** Author/committer must match the uploader
   unless forge rights exist, so the synthetic commit uses the pushing user's identity
   (`IdentifiedUser#newCommitterIdent`) for both — no forge privilege is needed or granted.
6. **Change numbers can be reserved.** `ChangeInserter` takes the `Change.Id` as input, so the
   number can be drawn from `Sequences` and durably recorded *before* insertion. Recovery
   then checks NoteDb (`ChangeNotes.Factory#createChecked`) for that exact number instead of
   relying on the eventually consistent index. Duplicate Changes are impossible by
   construction.
7. **No custom Change metadata facility is authoritative.** Custom keyed values exist in all
   three releases but are user-editable through REST, so they cannot be trusted for mapping.
   Plugin-owned refs in the project repository under `refs/meta/branch-review/` are used
   instead, protected by `CommitValidationListener`, `RefOperationValidationListener` and
   `MergeValidationListener` (tested). Read visibility of that namespace follows the project's
   `refs/*` READ ACL; see [security](security.md).
8. **Submit types differ in commit count.** `MERGE_ALWAYS` always adds a merge commit;
   `MERGE_IF_NECESSARY` adds one unless the change fast-forwards. `FAST_FORWARD_ONLY`,
   `REBASE_IF_NECESSARY`, `REBASE_ALWAYS` and `CHERRY_PICK` add exactly one commit for a
   single-parent change. `MergeValidationListener` runs on every Submit attempt (including
   `MergeOp` retries) with the effective submit type available through `ChangeData`, so policy
   is enforced at Submit, not only at Change creation.
9. **Acceptance-test enforcement of ref update contexts.** Gerrit's test repository manager
   rejects ref updates outside a `RefUpdateContext`. Plugin writes therefore open the matching
   context (`PLUGIN`, `CHANGE_MODIFICATION`, `BRANCH_MODIFICATION`), which also documents
   intent in production.
10. **Packaging detail found only by the real-server test:** a plugin declaring
    `Gerrit-SshModule` must also declare `Gerrit-ApiType: plugin`, otherwise the loader rejects
    it (`InvalidPluginException`). The in-process acceptance framework does not read the
    manifest and did not catch this; the black-box harness did.

## Proof of concept

The spike was the smallest version of the production plugin (post-receive hook + reserved
Change insertion), exercised first on 3.14.4 and then 3.12.11 and 3.13.10.

| # | Required demonstration | How | Result |
| --- | --- | --- | --- |
| 1 | Authenticated push to an ordinary feature ref accepted with normal receive behaviour | In-process acceptance (`CoreWorkflowIT`), real `git` over SSH/HTTP (`blackbox.py`) | Passed on 3.12.11, 3.13.10, 3.14.4 |
| 2 | One valid synthetic commit attached to a new WIP Change through normal machinery | `ChangeInserter` in `BatchUpdate`, WIP set, validators run | Passed |
| 3 | Later push adds a patch set to the same Change | `PatchSetInserter`, same Change number and `Change-Id` | Passed |
| 4 | Numeric Change URL received before the receive session closes, SSH and smart HTTP | Black-box asserts exact `remote: branch-review: …` lines including `http://localhost:<port>/r/c/<project>/+/<n>` (URL prefix `/r`), then fetches that URL | Passed on all three releases |
| 5 | Permission negative case: source push allowed, review creation denied | `BLOCK push refs/for/refs/heads/master`; source updated, no Change, explicit message | Passed (in-process and black-box) |
| 6 | Normal Submit adds exactly one commit | In-process (`SubmitIT`): every supported submit type, ancestry and count asserted with JGit. Black-box: REST Submit under `REBASE_IF_NECESSARY`, asserted with `git rev-list`/`merge-base` | Passed |
| 7 | Injected failure at the persistence boundary leaves a detectable, recoverable state without duplicates | `RecoveryIT`: validator rejects insertion; reservation left before insertion; reservation left after insertion; recovery service retry | Passed |

Commands (from a clean clone, after `mise install`):

```sh
mise run test:integration            # 3.14: acceptance + black-box
bash tools/scripts/test.sh integration 3.12
bash tools/scripts/test.sh integration 3.13
```

## Limitations and remaining blockers

- The plugin is designed for a **single primary** server. The in-process lock serializes work
  per branch; state-ref compare-and-swap prevents lost updates, but multi-primary operation
  (e.g. with a shared NoteDb across primaries) was not tested and is not supported.
- Post-receive output requires a side-band capable client (all supported Git versions are).
  A push that sends no command (already up to date) produces no server output; use
  `branch-review status`.
- Work runs while the client waits, bounded by `pushWaitSeconds` (default 60 s). Longer work
  continues in the background and is reported as "still running".
- No remaining blocker for the binding requirements was found.
