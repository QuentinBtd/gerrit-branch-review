# Test evidence

Outcomes: **passed**, **failed**, **not run**, **not supported**. A workflow file that never ran
on GitHub is not evidence; all results below are local runs.

## Environment and revision

- Date: 2026-10-22 (UTC).
- Host: macOS 26.6.1 arm64, Docker 29.6.1 (arm64 images), mise 2026.10.17 (project minimum
  2026.9.18), tools from `.mise.toml`/`mise.lock`.
- Revision: the repository has **no commit yet**. Final runs used a throwaway local commit of the
  complete working tree (`e7136b1163447bb194b13bc095521c576e240eeb`, "clean clone" snapshot in a
  separate directory, created with `git ls-files --cached --others --exclude-standard`), which
  also exercised the documented clean-clone path. The snapshot is not published.
- Gerrit: 3.12.11, 3.13.10, 3.14.4 (artifacts and image digests in `tools/gerrit-releases.json`,
  re-verified against upstream with `python3 tools/scripts/pin_gerrit.py --check`: passed).

## Results

| Area | Command | Outcome |
| --- | --- | --- |
| Clean clone setup | `mise trust && mise install && mise run doctor` | passed (all prerequisites and 17 pinned tools reported) |
| Hooks install | `mise run hooks:install` | passed (pre-commit and commit-msg installed) |
| Commit-msg hook | `git commit -m "updated readme"` with hooks installed | passed (rejected: "subject must follow Conventional Commits") |
| Hooks on all files | `mise run hooks:run` | passed (after fixing a buildifier pathspec bug and a missing executable bit it found) |
| Local CI equivalent | `mise run verify` (format, hooks, lint, unit tests, build of 3 series) | passed |
| Unit tests | `mise run test` (also per series in the matrix) | passed: 38 cases × 3 series |
| Acceptance tests | `bash tools/scripts/test.sh acceptance <series>` | passed: 101 cases × 3 series (`CoreWorkflowIT` 14, `StateAndTextIT` 20, `SubmitIT` 25, `PermissionsIT` 12, `LifecycleIT` 14, `RecoveryIT` 11, `RoutingIT` 4) |
| Black-box tests | `bash tools/scripts/test.sh blackbox <series>` | passed: 9 cases × 3 series |
| Full matrix | `mise run test:matrix` | passed (exit 0) for 3.12.11, 3.13.10, 3.14.4; re-run on 2026-10-22 after adding `syncEditsToBranch` (main checkout, branch `feat/initial-branch-review`) |
| Packaging | `mise run package` | passed: JAR manifest/content checks for all series |
| Reproducibility | `package`, `bazel clean`, `package`, compare `.sha256` | passed: byte-identical JARs for the final 0.0.0 tree (3.12 `2c308457…a2a5`, 3.13 `f9a39372…8de9`, 3.14 `b9cfeb44…8ca3`); cross-host reproducibility not tested |
| Release policy | `node --test tools/release/version-policy.test.mjs` | passed: 7 cases (0.1.0 bootstrap, fix→0.1.1, feat→0.2.0, breaking→0.2.0 with notes, 1.0.0+breaking→2.0.0, PR title/files, pinned version) |
| Release config schema | `tools/scripts/check-config.sh` (schema of release-please 17.11.2) | passed |
| Local release build / verify | see "Release simulation" below | see below |
| Renovate config | `mise run renovate:validate` (pinned image, `--strict`) | passed (repository and bot configuration) |
| Renovate extraction | `bash tools/scripts/renovate.sh extract` (offline, `--platform=local`) | passed: 59 dependencies (bazel-module 2, bazelisk 1, mise 17, github-actions 31 incl. composite action, regex: Gerrit 3, Renovate image 1, mise min_version 1, pre-commit 0) |
| Renovate lookup dry run | `mise run renovate:dry-run` | not run (needs a token for the remote repository and the App setup) |
| `release:plan` | `mise run release:plan` | not run (needs the repository on GitHub with history) |
| `release:publish` | | not run (outward-facing; requires a published repository and credentials) |
| GitHub workflows | `.github/workflows/*.yml` | not run on GitHub; validated with actionlint only |
| Linux host | | not run locally (CI targets ubuntu-24.04; lockfile has linux entries) |

Reports: JUnit XML and black-box server logs were produced under `build/test-results/<series>/`
of the snapshot (`TEST-*.xml`, `blackbox/TEST-blackbox-<series>.xml`, `blackbox/gerrit.log`).

## Behaviour matrix coverage

| Area | Covered by | Notes |
| --- | --- | --- |
| Opt-in | `CoreWorkflowIT.disabledWithoutConfigFile`, `explicitlyDisabledAndParentConfigDoNotEnable`, `invalidConfigIsReportedAndNothingIsMutated`, `ConfigParserTest` | |
| Ref filtering | `CoreWorkflowIT.refFiltering`, `targetPushIsIgnored`, `ConfigParserTest.hardExclusionsWinOverPatterns`, `refPatternGrammar` | |
| Config updates | `CoreWorkflowIT.configCacheFollowsUpdates`, `LifecycleIT.targetChangeDuringActiveLifecycleIsRefused`, `patternChangeDoesNotMoveOrDuplicate`, `PermissionsIT.configAndSourceInOnePushAreNotSynchronized` | |
| Creation | `CoreWorkflowIT.pushCreatesOneWipChangeAndUpdatesIt`, black-box `test_ssh_creation_and_http_update_report_real_url` | |
| Update | same, `StateAndTextIT` | |
| No-op / retry | `CoreWorkflowIT.identicalRetryAndEquivalentContentAddNoPatchSet`, `RecoveryIT.sourceAdvancingDuringProcessingIsPickedUp`, black-box `test_up_to_date_push_and_status_lookup` | |
| WIP / Ready | `StateAndTextIT.readyStaysReadyAndWipStaysWip` | A deterministic race between a WIP toggle and a running sync was not constructed; synchronization never writes WIP state (`PatchSetInserter` without `setWorkInProgress`) |
| Text | `TextTest`, `StateAndTextIT` (subject/body independently, trailers, UI message edit, reset) | |
| Human divergence | `StateAndTextIT.uiEditIsAppliedToTheBranch`, `editIsAppliedOnTopOfAConcurrentPush`, `editAlreadyInTheBranchIsRecognized`, `conflictingEditPausesWithExplanation`, `editorWithoutBranchPushRightKeepsThePause`, `forcePushReplacesConflictingEdit`, `forcePushKeepsNonConflictingEdit`, `forcePushOverridesWhenBackportDisabled`, `humanContentPatchSetPausesSynchronizationWhenBackportDisabled`, `unpublishedChangeEditIsNotTouched`, `guiRebaseIsRecognizedAsDerivative`; black-box `test_ui_edit_reaches_branch` | |
| Git graph | `SquashPlannerTest`, `LifecycleIT.forcePushRebaseAndTargetMergedIntoSource`, `unrelatedHistoryIsRejected`, `conflictIsReportedAndBlocksSubmit`, `SubmitIT.targetAdvancedBeforeSquashIsPreserved` | |
| Tree content | `SquashPlannerTest` (rename/delete, binary, modes, symlink, gitlink, empty, conflict) | |
| Identity | `PermissionsIT.anotherAuthorizedPusherUploadsWithoutTakingOwnership`, `CoreWorkflowIT.sourceChangeIdCannotHijackAnotherChange` | |
| Permissions | `PermissionsIT` (10 cases), black-box `test_source_push_allowed_review_denied` | |
| Push UX | black-box (SSH and smart HTTP, URL below `/r` prefix, project with `/`, multi-ref), `CoreWorkflowIT.multiRefPushReportsEachRef`, `onlyAcceptedCommandsAreSynchronized`, `rejectedAtomicPushSynchronizesNothing` | |
| Partial failure | `RecoveryIT` (rejected insertion, crash before/after insertion, recovery service) | |
| Concurrency | `RecoveryIT.concurrentSynchronizationCreatesOneChange` (8 parallel syncs → 1 Change) | Index lag not simulated; correctness does not use the index |
| Submit | `SubmitIT` (all supported submit types, blocked merge types, review gates) | |
| Target movement | `SubmitIT.targetAdvancesAfterPatchSetUnrelatedWorkIsKept`, `targetAdvancedBeforeSquashIsPreserved`, `mergeIfNecessaryBlockedWhenTargetMoved` | |
| Staleness | `SubmitIT.staleSourceBlocksSubmit`, `pushAfterMergeNeverAddsPatchSetToMergedChange` | |
| Cleanup | `SubmitIT.cleanup*`, black-box `test_submit_adds_one_commit_and_cleans_up` | |
| Lifecycle | `LifecycleIT`, `SubmitIT.branchReuseAfterSubmitRequiresTargetBasedHistory` | |
| Runtime | black-box `test_z_restart_keeps_association`, `test_zz_plugin_reload_keeps_working`; `RecoveryIT.stopWaitsFor*` (in-flight worker and in-flight manual REST sync during stop); black-box `test_up_to_date_push_and_status_lookup` exercises the real SSH `sync` command, which caught singletons duplicated in the plugin's SSH injector | The reload test found a real defect (duplicate metric registration on hot reload), fixed with `Gerrit-ReloadMode: restart`. Queue limits: `RecoveryIT.queueLimitAppliesToAllTasksAndReservesRoomForPushes` |
| Compatibility | full matrix above | |
| Tooling | clean-clone path, hooks, validators, packaging | |
| Releases | release fixtures, release simulation | Publication not run |
| Renovate | validation and extraction | Lookup/PR creation not run |

## Release simulation

Script: a throwaway clone of the snapshot, `mise run release:build`/`release:verify` with
explicit versions (no network writes).

| Step | Expected | Outcome |
| --- | --- | --- |
| `release:build 0.1.0-rc.1 HEAD` with `version.txt` 0.0.0 | assets built from a clean worktree, manifest `Implementation-Version: 0.1.0-rc.1`, checksums verify | passed |
| `release:build 0.0.0-rc.1 HEAD` | refused (not greater than 0.0.0) | passed |
| `release:build 0.1.0 HEAD` while unreleased | refused (version.txt/manifest mismatch) | passed |
| Simulated merged release PR (`version.txt` 0.1.0, manifest `{".": "0.1.0"}`, changelog entry, tag `v0.1.0`), `RELEASE_SKIP_MATRIX=1 release:verify 0.1.0 HEAD` | verify passes (format, hooks, lint, unit, build matrix) | passed |
| `release:build 0.1.0 HEAD` | 5 assets, checksums verify (3.12 `e772d3d5…`, 3.13 `c6dede4b…`, 3.14 `b91281fc…`) | passed |
| Same after a later commit (tag no longer at HEAD) | refused (tag points elsewhere) | passed |
| `release:build 0.1.0-rc.2` after 0.1.0 | refused | passed |
| `release:build 0.2.0-rc.1` after 0.1.0 | built | passed |
| `release:publish` (idempotent upload, draft publication) | | not run (outward-facing) |
