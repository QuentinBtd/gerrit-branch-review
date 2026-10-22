# Contributing

Thanks for helping. This file covers the repository conventions; the
[development guide](docs/development.md) covers setup and tasks.

## Workflow

1. Set up the toolchain: `mise trust && mise install && mise run doctor && mise run hooks:install`.
2. Create a branch, make focused commits.
3. Run `mise run verify` (and `mise run test:integration` for behaviour changes; use
   `mise run test:matrix` when touching Gerrit integration code).
4. Open a pull request. CI runs the same tasks; the integration matrix on 3.12, 3.13 and 3.14 is
   required.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) are required (checked
by the `commit-msg` hook and in CI): `feat(sync): …`, `fix(config): …`, `docs: …`, `ci: …`,
`build: …`, `chore: …`, `test: …`, `refactor: …`, `perf: …`, `deps: …`. Mark breaking changes
with `!` and a `BREAKING CHANGE:` paragraph. Pull requests are squash-merged and the PR title
becomes the commit subject, so the title must follow the same rules. See
[releasing](docs/releasing.md) for how types map to versions.

This convention applies to this repository only. Commits on branches reviewed *by* the plugin
in a Gerrit project are not constrained by it.

## Code

- Java is formatted with google-java-format, Starlark with buildifier, shell with shfmt, Python
  with ruff (`mise run fmt`).
- Keep version-sensitive Gerrit internals in the integration classes; keep logic that can be a
  pure function (config, text, squash planning) free of Gerrit server types and unit-tested.
- Behaviour changes need tests at the right layer: unit for pure logic, acceptance tests for
  Gerrit integration, black-box tests for transport/packaging behaviour.
- Never add tests that only assert that a file contains a string.

## Licensing

By contributing you agree that your contribution is licensed under the Apache License 2.0.
New source files carry the SPDX header used in the existing files.
