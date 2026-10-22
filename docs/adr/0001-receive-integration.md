# ADR 0001: Receive integration through a post-receive hook

- Status: accepted
- Date: 2026-10-22

## Context

A push to an ordinary branch must (a) keep Gerrit's normal receive behaviour, (b) produce a
review Change through normal Change machinery, and (c) print the real Change URL in the
`git push` output. Candidate integration points were examined in the 3.12.11, 3.13.10 and
3.14.4 sources ([investigation](../api-investigation.md)):

- Replacing or wrapping Gerrit's `PreReceiveHook`: not possible for plugins (Gerrit sets it in
  `AsyncReceiveCommits`), and doing work before acceptance would give validators side effects.
- `CommitValidationListener` / `RefOperationValidationListener`: run before acceptance; any
  Change created there would exist even if the push is later rejected (e.g. atomic push).
- `GitReferenceUpdatedListener`: after commit but with no response channel to the client.
- `PostReceiveHook` registered in Gerrit's `DynamicSet<PostReceiveHook>`: runs after Gerrit
  committed the ref updates and JGit sent the status report, while the side-band is still open.

## Decision

Register a `PostReceiveHook` (and a `ReceivePackInitializer` to learn the project of each
`ReceivePack`). For every `OK` command on an eligible branch the hook submits a
synchronization task to a bounded plugin executor, waits up to `pushWaitSeconds`, and writes
one result block per ref with `ReceivePack#sendMessage`. It never alters command results or
Gerrit's own messages.

## Consequences

- The source update is already committed when synchronization runs. A failure is reported as
  "accepted but not synchronized"; it is never turned into a rejected push, and no source ref is
  rolled back.
- Push output is synchronous on SSH and smart HTTP (proven on all three releases).
- A push that sends no command produces no output; `branch-review status` covers that case.
- The receive thread waits at most `pushWaitSeconds`; slow work continues in the background
  and is reported as "still running".
- Synchronization runs as the pushing user, so every Gerrit permission check applies to them.
