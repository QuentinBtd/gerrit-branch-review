# Changelog

## [0.3.0](https://github.com/QuentinBtd/gerrit-branch-review/compare/v0.2.1...v0.3.0) (2026-10-23)


### Features

* **config:** option to create Changes ready for review ([af87504](https://github.com/QuentinBtd/gerrit-branch-review/commit/af875049f5d73a2a286506ce5c82991ccac317e3))
* **config:** option to create Changes ready for review ([b2bde49](https://github.com/QuentinBtd/gerrit-branch-review/commit/b2bde491c2082777de17cd525b15b136513c8c07))

## [0.2.1](https://github.com/QuentinBtd/gerrit-branch-review/compare/v0.2.0...v0.2.1) (2026-10-23)


### Bug Fixes

* **sync:** release the worker slot before publishing the result ([c7407f8](https://github.com/QuentinBtd/gerrit-branch-review/commit/c7407f8fd27e2dbad04dede7750dcd91f5d02f16))
* **sync:** release the worker slot before publishing the result ([b7e5f81](https://github.com/QuentinBtd/gerrit-branch-review/commit/b7e5f81f44553b6838a5157349c37a6dcd6ad9ba))

## [0.2.0](https://github.com/QuentinBtd/gerrit-branch-review/compare/v0.1.0...v0.2.0) (2026-10-23)


### Features

* **config:** route source branches to several target branches ([a192c5b](https://github.com/QuentinBtd/gerrit-branch-review/commit/a192c5b7a20096a85fe5695b276882da81f365b4))
* **config:** route source branches to several target branches ([a593566](https://github.com/QuentinBtd/gerrit-branch-review/commit/a5935661b9901ab5901520d14e90d30abe799ef7))

## 0.1.0 (2026-10-22)


### Features

* initial branch-review Gerrit plugin ([035b0a7](https://github.com/QuentinBtd/gerrit-branch-review/commit/035b0a78fb0b22ca525b701233176628242000b4))
* **sync:** carry edits made in Gerrit back to the source branch ([d161b01](https://github.com/QuentinBtd/gerrit-branch-review/commit/d161b0157b69f908a39d0739c3965b03569b6140))


### Bug Fixes

* close fourth review round on concurrent moves, privacy and submit cost ([c2d551e](https://github.com/QuentinBtd/gerrit-branch-review/commit/c2d551e424339d7d5b050dd4bebf71795bcb4d27))
* close review findings on submit, repair, validation and queueing ([996071d](https://github.com/QuentinBtd/gerrit-branch-review/commit/996071ddd85a8c0ad5387ec3c63923b5e123ba58))
* close second review round on submit, targets, recovery and cleanup ([0ed4156](https://github.com/QuentinBtd/gerrit-branch-review/commit/0ed41569af8940ce59c8f9cef225186809e4cb36))
* close third review round on batch submit, cleanup, visibility and edits ([105ff12](https://github.com/QuentinBtd/gerrit-branch-review/commit/105ff1222bf21ea8d520db974c7ce3b7e0919d96))
* run manual sync on the executor and share singletons with SSH ([020fa33](https://github.com/QuentinBtd/gerrit-branch-review/commit/020fa33b7720f892872e39ed1875e9303b6b25b6))
* **sync:** let a force push override conflicting Gerrit edits ([18f8f25](https://github.com/QuentinBtd/gerrit-branch-review/commit/18f8f25eeecc72940910945506722a8c4cbe3672))
* **sync:** offer keeping the branch when an edit conflicts ([84e2589](https://github.com/QuentinBtd/gerrit-branch-review/commit/84e2589ab5b9b75928e15785fdb12551792e1526))
* **sync:** wait for running workers before the plugin stops ([3b4c161](https://github.com/QuentinBtd/gerrit-branch-review/commit/3b4c161737ac6d683d61c770112044100f48aa2f))

## Changelog

All notable changes to this project are documented in this file by
[Release Please](https://github.com/googleapis/release-please). The project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html); see
[docs/releasing.md](docs/releasing.md) for the `0.x` policy.
