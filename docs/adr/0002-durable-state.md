# ADR 0002: Durable lifecycle state in plugin-owned refs, with reserved Change numbers

- Status: accepted
- Date: 2026-10-22

## Context

The branch-to-Change association must survive restarts, be safe under concurrent pushes and
crashes, and never produce a duplicate Change. Branch names, topics, footers, hashtags and
index queries are not reliable uniqueness keys. Gerrit's custom keyed values exist in all
supported releases but are user-editable.

## Decision

Store state in the project repository:

- `refs/meta/branch-review/sources/<aa>/<sha256(sourceRef)>` → commit with `lifecycle.json`
  (project, source ref, fixed target ref, lifecycle id, Change number, synthetic `Change-Id`,
  last synchronized source/target/synthetic commit, sync state, text ownership, pending
  reservation, merge/cleanup record). Every update is a new commit on the previous one (audit
  trail) and a compare-and-swap against the value read.
- `refs/meta/branch-review/changes/<nn>/<number>` → `change.json` linking a Change back to its
  lifecycle. Written *before* the Change is inserted.

Creation protocol: allocate the Change number from `Sequences`, write the link, write the
lifecycle with a `pending` reservation (number, patch set, commit, source/target ids), insert
through `BatchUpdate`, then clear the reservation. Any later run first resolves an existing
reservation against NoteDb: if the Change/patch set exists with the reserved commit, it is
adopted; otherwise the reservation is discarded. The synthetic `Change-Id` is derived from the
lifecycle id, never from source commit footers.

Users cannot write the namespace: `ConfigValidator` (commits), `StateRefProtector` (ref
operations) and `SubmitGuard` (Submit) reject it. An in-process striped lock serializes work
per (project, ref); the CAS protects against anything that bypasses the lock.

## Consequences

- No index dependency for correctness; NoteDb is authoritative.
- State is backed up, replicated and restored with the repository. Ref-updated events are
  fired for state refs so replication sees them.
- Read visibility of `refs/meta/branch-review/*` follows the project's READ ACL on `refs/*`.
  Sites that hide branches should block READ on `refs/meta/branch-review/*` for non-owners
  (documented in [security](../security.md)).
- Supported topology is a single primary; multi-primary would additionally need a
  cluster-wide lock and was not tested.
