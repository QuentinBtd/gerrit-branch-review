Review ordinary feature branch pushes as a single Gerrit Change.

When a project opts in with `branch-review.config` on `refs/meta/config`, every
push to a matching source branch is mirrored into one Work-in-Progress Change
that targets the configured branch. Each later push adds a synthetic squash
patch set to the same Change. Review and Submit work as for any other Change.

See the project documentation for configuration, permissions and recovery.
