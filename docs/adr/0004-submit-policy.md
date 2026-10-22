# ADR 0004: Normal Submit with a submit-time guard

- Status: accepted
- Date: 2026-10-22

## Context

Submit must use Gerrit's ordinary action, labels and submit requirements, add exactly one
contribution commit, and not merge stale content. Project submit types vary.

## Decision

No custom Submit action. A `MergeValidationListener` (`SubmitGuard`) runs for every change on
every Submit attempt and, for Changes linked to a lifecycle of an enabled project:

- accepts `FAST_FORWARD_ONLY`, `REBASE_IF_NECESSARY`, `REBASE_ALWAYS`, `CHERRY_PICK`;
- accepts `MERGE_IF_NECESSARY` only when the patch set's parent is the current target tip
  (fast-forward); otherwise asks for a rebase;
- rejects `MERGE_ALWAYS`;
- requires the lifecycle to be `SYNCED` (or `SOURCE_DELETED`), the source ref to still point at
  the synchronized source commit, and the submitted patch set to be the generated one or a
  message-only edit / pure Gerrit rebase of it.

The effective submit type comes from `ChangeData#submitTypeRecord()`, so per-project and rule
based submit types are evaluated at Submit time. Project-wide settings are never changed.

After the merge, `MergeHandler` (a `ChangeMergedListener`) closes the lifecycle and runs the
optional cleanup ([architecture](../architecture.md#submit-and-cleanup)).

## Consequences

- One-commit history is enforced at the point where it matters; tested for every supported
  type, including a target that advanced after the patch set (rebase types) and the blocked
  merge cases.
- Race boundary: the source ref check reads a mutable ref; a push landing after the check but
  before the target update is not part of the submitted content. That push then finds the
  lifecycle merged and is reported as not synchronized; the source is never deleted in that
  case because cleanup compares the exact submitted source commit.
- If the plugin is disabled for a project, linked Changes behave as ordinary Changes.
