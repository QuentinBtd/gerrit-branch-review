Review ordinary feature branch pushes as a single Gerrit Change.

When a project opts in with `branch-review.config` on `refs/meta/config`, every
push to a matching source branch is mirrored into one Work-in-Progress Change
that targets the configured branch. Each later push adds a synthetic squash
patch set to the same Change. Review and Submit work as for any other Change. The change page shows the
source branch in its metadata block, with a copy button and a link to the
repository browser (e.g. gitiles) while the branch exists.

See the project documentation for configuration, permissions and recovery.
