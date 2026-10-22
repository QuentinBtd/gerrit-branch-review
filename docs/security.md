# Security model

## Trust boundaries and threats

| Asset / boundary | Threat | Mitigation | Test |
| --- | --- | --- | --- |
| Review creation on the target | Pusher with branch rights but no review rights creates or updates review Changes | Synchronization runs as the pusher; `READ` + `CREATE_CHANGE` on the target checked before anything is reserved; `PatchSetInserter` checks `ADD_PATCH_SET`; private/hidden Changes fail `READ` | `PermissionsIT.sourcePushAllowedButReviewCreationDenied`, `otherUserCannotAddPatchSetWithoutPermission`; black-box `test_source_push_allowed_review_denied` |
| Identity / attribution | Synthetic commits forge someone else's identity | Author = committer = the pusher's own Gerrit identity; Gerrit's normal author/committer validators still run; original authors appear only as text | `CoreWorkflowIT`, `PermissionsIT.anotherAuthorizedPusherUploadsWithoutTakingOwnership` |
| Change ownership | A later pusher takes over the Change | Owner is the first pusher; later pushers become uploaders only | same as above |
| Mapping integrity | User forges mapping via refs, footers or topics | Mapping lives only in `refs/meta/branch-review/*`; user commits, ref operations and Submits targeting that namespace are rejected; footers/topics/hashtags are never read; synthetic `Change-Id` derives from the lifecycle id | `PermissionsIT.stateRefsCannotBeWrittenByUsers`, `CoreWorkflowIT.sourceChangeIdCannotHijackAnotherChange` |
| Configuration | Malicious/invalid config enables unexpected behaviour | Explicit per-project opt-in, strict parser, validation on push and Submit, no inheritance | `ConfigParserTest`, `PermissionsIT.invalidConfigUpdateIsRejected`, `CoreWorkflowIT.explicitlyDisabledAndParentConfigDoNotEnable` |
| Branch writes for Gerrit edits | The plugin writes a source branch on someone's behalf | Only a fast-forward commit containing exactly the human delta of a published patch set; authored by and permission-checked (`push` on the branch) for that patch set's uploader; compare-and-swap on the tip; can be disabled per project (`syncEditsToBranch = false`) | `StateAndTextIT.uiEditIsAppliedToTheBranch`, `editorWithoutBranchPushRightKeepsThePause`, `conflictingEditPausesWithExplanation`, black-box `test_ui_edit_reaches_branch` |
| Source branch deletion | Cleanup deletes unreviewed or foreign work | Only after merge; exact submitted commit CAS; current config and eligibility rechecked; submitter must hold delete; target/protected refs never eligible | `SubmitIT.cleanup*` |
| Information disclosure | Status API confirms hidden branch names or private Changes | `READ` on the branch required (404 otherwise); Change fields only if the caller can see the Change | `PermissionsIT.hiddenSourceBranchIsNotDisclosed`, `privateChangeIsNotDisclosedInStatus` |
| Resource exhaustion | Huge histories or push floods block receive threads | `maxCommits` bound checked before sorting; bounded queue (`maxQueued`); push waits at most `pushWaitSeconds`; executor shut down on unload | `SquashPlannerTest.limitIsEnforced` |
| Shell / injection | Ref names or text reach a shell | No shell is used by the server plugin; JGit only; ref names validated; control/bidi characters stripped from generated text | `TextTest`, `ConfigParserTest` |
| Privilege escalation via delayed work | Recovery or cleanup acts with more rights than the original actor | Retries run as the last pushing user; cleanup as the submitter's permissions; no service account, no administrator capability | code review: `RecoveryService`, `MergeHandler` |

## ACL matrix (tested combinations)

| Actor permissions | Push to `refs/heads/feature/x` | Review Change | Notes |
| --- | --- | --- | --- |
| create/push on `refs/heads/feature/*` + push on `refs/for/refs/heads/master` | accepted | created / updated | normal case |
| branch rights, push on `refs/for/...` blocked | accepted | not created; message explains | |
| branch rights, not owner, `addPatchSet` blocked | accepted | not updated; message explains | owner can always add patch sets (Gerrit rule) |
| branch rights without force | non-fast-forward rejected by Gerrit | unchanged | |
| no READ on the branch | — | status/sync return 404 | |
| owner of `refs/meta/*` with push+force+delete | writes to `refs/meta/branch-review/*` rejected | — | plugin rule, independent of ACLs |

## Visibility considerations

- WIP is not an access control mechanism; WIP Changes are visible like any other Change.
- The generated body and patch set description name the source branch and list abbreviated
  source commit ids. If a project hides some branches from people who can see the target
  branch's Changes, those names become visible on the Change. Avoid enabling such branches as
  sources, or exclude them with `excludeBranchPattern`.
- `refs/meta/branch-review/*` is readable by whoever has READ on it (by default READ on
  `refs/*`). Sites that hide branches should add, in All-Projects:

  ```ini
  [access "refs/meta/branch-review/*"]
      read = block group Registered Users
  ```

  Status lookups go through the permission-filtered API, which does not need this namespace to
  be readable.

## Audit trail

Every state change is a commit on the lifecycle ref (`git log refs/meta/branch-review/sources/…`)
by the server identity with a message describing it; the Change history records the uploader
of each patch set; logs record actor, refs, outcome and Change/patch set. No secret, token or
commit body is logged.

## Reporting

See [SECURITY.md](../SECURITY.md).
