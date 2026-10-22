# Compatibility

Support claims cover exactly the combinations below. A passing patch release proves that
patch release, not every historical or future patch of its series; the matrix is extended
(and this table updated) before claiming more.

| Gerrit release | Series | Java runtime | Artifact | Build | In-process acceptance | Black-box (packaged JAR, real SSH + smart HTTP) | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3.12.11 (`748845aa`) | 3.12 | Java 21: acceptance on Bazel `remotejdk_21`, black-box on the image's JRE 21.0.6+7 | `branch-review-<version>-gerrit-3.12.jar` | passed | passed (95 cases) | passed (9 cases) | supported |
| 3.13.10 (`f9e70cda`) | 3.13 | Java 21: `remotejdk_21` / image JRE 21.0.8+9 | `branch-review-<version>-gerrit-3.13.jar` | passed | passed (95 cases) | passed (9 cases) | supported |
| 3.14.4 (`6d038645`) | 3.14 | Java 21: `remotejdk_21` / image JRE 21.0.10+7 | `branch-review-<version>-gerrit-3.14.jar` | passed | passed (95 cases) | passed (9 cases) | supported (reference) |

Evidence, commands and dates: [test-evidence.md](test-evidence.md).

## Artifacts

- One JAR per series, all from the same sources and the same plugin version. Use the JAR whose
  suffix matches the server's series; cross-series loading is neither tested nor claimed.
- Every JAR contains only the plugin's own classes, its documentation and `LICENSE`, and the
  manifest entries `Gerrit-PluginName: branch-review`, `Gerrit-ApiType: plugin`,
  `Gerrit-ReloadMode: restart`, `Gerrit-Module`, `Gerrit-SshModule`,
  `Implementation-Version: <plugin version>` and `Gerrit-ApiVersion: <exact Gerrit release>`
  (checked by `tools/scripts/check_jar.py` in every build and release).
- Checksums of a release are published in `branch-review-<version>.sha256`. Checksums of local
  builds of the unreleased `0.0.0` tree are listed in the test evidence; JARs were byte-identical
  across two clean builds.

## Requirements and limitations

- Java 21 runtime, as required by all three Gerrit series.
- Single primary server. Replicas, multi-primary/high-availability setups and external writers
  of source refs (e.g. replication *into* an enabled project) are not supported.
- Supported submit types for branch-review Changes: `FAST_FORWARD_ONLY`, `REBASE_IF_NECESSARY`,
  `REBASE_ALWAYS`, `CHERRY_PICK`, and `MERGE_IF_NECESSARY` when the Change fast-forwards.
  `MERGE_ALWAYS` is rejected at Submit. With whole-topic Submit, reviews that would need a merge
  commit under `MERGE_IF_NECESSARY` are rejected; submit them separately.
- Git clients must support side-band (all maintained Git versions do) to see push output.
- The optional PolyGerrit source-branch link is not implemented.

## Updating

- New patch release of a supported series: Renovate proposes it (`fix(gerrit)`), the allowlisted
  `pin_gerrit.py` re-pins checksums and the image digest, and CI runs the full matrix. Check
  whether the release changed its bundled BouncyCastle version.
- New series: add it to `GERRIT_SERIES`, `tools/gerrit-releases.json`, the workflow matrices,
  `tools/scripts/lib.sh` and this table; run the matrix; release as a `feat`.
- Dropping a series is a breaking change.
