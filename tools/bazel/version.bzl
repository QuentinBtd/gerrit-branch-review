"""Exposes the plugin version to BUILD files.

The single version source is version.txt (maintained by Release Please). An explicit
prerelease build may override it with --repo_env=BRANCH_REVIEW_VERSION=X.Y.Z-rc.N; the
release scripts validate that such an override is a prerelease of the next version.
"""

_SEMVER = "0123456789."

def _valid(v):
    core, _, pre = v.partition("-")
    parts = core.split(".")
    if len(parts) != 3 or not all([p and all([c in _SEMVER for c in p.elems()]) for p in parts]):
        return False
    return pre == "" or (pre.startswith("rc.") and pre[3:].isdigit())

def _version_repo_impl(rctx):
    version = rctx.read(rctx.attr.src).strip()
    override = rctx.getenv("BRANCH_REVIEW_VERSION")
    if override:
        version = override.strip()
    if not _valid(version):
        fail("invalid plugin version %r (expected X.Y.Z or X.Y.Z-rc.N)" % version)
    rctx.file("BUILD.bazel", "exports_files(['version.bzl'])\n")
    rctx.file("version.bzl", "PLUGIN_VERSION = %r\n" % version)

_version_repo = repository_rule(
    implementation = _version_repo_impl,
    attrs = {"src": attr.label(allow_single_file = True)},
)

def _plugin_version_impl(mctx):
    _version_repo(name = "branch_review_version", src = Label("//:version.txt"))
    return mctx.extension_metadata(reproducible = True)

plugin_version = module_extension(implementation = _plugin_version_impl)
