# Changelog

## [0.7.1](https://github.com/QuentinBtd/gerrit-branch-review/compare/v0.7.0...v0.7.1) (2026-11-18)


### Bug Fixes

* **ui:** reload the UI script after a plugin upgrade ([a9a852f](https://github.com/QuentinBtd/gerrit-branch-review/commit/a9a852fec208d1c77b00997870aafeea6f2ce60c))
* **ui:** reload the UI script after a plugin upgrade ([bd7bc69](https://github.com/QuentinBtd/gerrit-branch-review/commit/bd7bc6993c6cb4bee247ecb37464bf42ee9e70f7))

## [0.7.0](https://github.com/QuentinBtd/gerrit-branch-review/compare/v0.6.0...v0.7.0) (2026-11-18)


### Features

* **ui:** delete the source branch from a closed Change ([6fa078e](https://github.com/QuentinBtd/gerrit-branch-review/commit/6fa078e460ae4fff76c607d054e728dd6eaaf60d))
* **ui:** delete the source branch from a closed Change ([5f98403](https://github.com/QuentinBtd/gerrit-branch-review/commit/5f98403694380884e0370018e44ec68ac3e5aaab))
* **ui:** strike through the source branch once it is deleted ([6ca2b05](https://github.com/QuentinBtd/gerrit-branch-review/commit/6ca2b055b500bdc4817194e83097b022482be0f9))


### Bug Fixes

* **ui:** refuse source deletion in read-only projects and on restored Changes ([a785760](https://github.com/QuentinBtd/gerrit-branch-review/commit/a785760faf2ccb14f9ff7ad8973aa13da2133eb8))

## [0.6.0](https://github.com/QuentinBtd/gerrit-branch-review/compare/v0.5.1...v0.6.0) (2026-11-18)


### Features

* **ui:** show the source branch on the change page ([c514386](https://github.com/QuentinBtd/gerrit-branch-review/commit/c514386656b6886758fd1c875ffc3a5dc6a552e5))

## [0.5.1](https://github.com/QuentinBtd/gerrit-branch-review/compare/v0.5.0...v0.5.1) (2026-11-18)


### Bug Fixes

* **build:** prefix the plugin version with "v" in the manifest ([c8e94f0](https://github.com/QuentinBtd/gerrit-branch-review/commit/c8e94f09dcfd99f440f1f7f0fcb957ed8646a91c))
* **build:** prefix the plugin version with "v" in the manifest ([fbb9a35](https://github.com/QuentinBtd/gerrit-branch-review/commit/fbb9a35a3accf44acb11950070fe14f17658c084))

## [0.5.0](https://github.com/QuentinBtd/gerrit-branch-review/compare/v0.4.0...v0.5.0) (2026-11-16)


### Features

* **config:** make source commit comments configurable ([8ba452e](https://github.com/QuentinBtd/gerrit-branch-review/commit/8ba452ea5de28bd4be3bb9ad31fdc6d83e458e91))
* **sync:** publish source commit messages in change discussions ([8976a8b](https://github.com/QuentinBtd/gerrit-branch-review/commit/8976a8b2fec1486d52960468b6e1fec80d029320))


### Bug Fixes

* preserve skipped discussion baselines and WIP notifications ([6624b97](https://github.com/QuentinBtd/gerrit-branch-review/commit/6624b975317b07502b61b3188c1d7d162c12c712))
* **recovery:** sweep publication evidence of ended lifecycles ([91a9c17](https://github.com/QuentinBtd/gerrit-branch-review/commit/91a9c1745ba699fe9b20157393a2580655858f58))
* **sync:** bound source message evidence and baselines ([0d0892d](https://github.com/QuentinBtd/gerrit-branch-review/commit/0d0892dfc6abcbdbe573f43c4225fd70e9ed0d64))
* **sync:** never block synchronization on source commit discussions ([e349bf5](https://github.com/QuentinBtd/gerrit-branch-review/commit/e349bf5f569af5d9e91d664757571cc8f9ac9b0b))

## [0.4.0](https://github.com/QuentinBtd/gerrit-branch-review/compare/v0.3.0...v0.4.0) (2026-10-23)


### ⚠ BREAKING CHANGES

* **config:** a branch-review.config with enabled = true in a parent project (All-Projects included), previously ignored, now enables the plugin for all its descendants. Children opt out with enabled = false. Config updates naming a missing target branch are no longer rejected.

### Features

* **config:** inherit branch-review.config from parent projects ([f232055](https://github.com/QuentinBtd/gerrit-branch-review/commit/f2320551d9a13f236bddf85816dfd01eeeaad98f))
* **config:** inherit branch-review.config from parent projects ([9f830c3](https://github.com/QuentinBtd/gerrit-branch-review/commit/9f830c33a572a771ca43b228b4bd9c82540e3251))

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
