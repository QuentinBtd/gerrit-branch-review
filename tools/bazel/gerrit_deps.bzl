"""Declares the Gerrit API artifacts of every supported series from tools/gerrit-releases.json.

That JSON file is the single source of truth for exact Gerrit patch releases, their artifact
checksums, the BouncyCastle version each WAR ships, and the container image used by the
black-box tests. tools/scripts/pin_gerrit.py recomputes checksums and digests after a version
change.
"""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

_MAVEN = "https://repo1.maven.org/maven2"

def _releases_repo_impl(rctx):
    rctx.file("BUILD.bazel", "exports_files(['releases.bzl'])\n")
    rctx.file("releases.bzl", rctx.attr.content)

_releases_repo = repository_rule(
    implementation = _releases_repo_impl,
    attrs = {"content": attr.string()},
)

def _gerrit_impl(mctx):
    data = json.decode(mctx.read(Label("//tools:gerrit-releases.json")))
    releases = {}
    bouncycastle = {}
    for series, r in data["series"].items():
        suffix = series.replace(".", "_")
        v = r["version"]
        if not v.startswith(series + "."):
            fail("Gerrit %s pinned to %s, outside its series" % (series, v))
        releases[series] = v
        bouncycastle[series] = r["bouncycastle"]
        http_jar(
            name = "gerrit_plugin_api_" + suffix,
            sha256 = r["pluginApiSha256"],
            urls = ["%s/com/google/gerrit/gerrit-plugin-api/%s/gerrit-plugin-api-%s.jar" % (_MAVEN, v, v)],
        )
        http_jar(
            name = "gerrit_acceptance_framework_" + suffix,
            sha256 = r["acceptanceFrameworkSha256"],
            urls = ["%s/com/google/gerrit/gerrit-acceptance-framework/%s/gerrit-acceptance-framework-%s.jar" % (_MAVEN, v, v)],
        )
    for version, artifacts in data["bouncycastle"].items():
        for artifact, sha256 in artifacts.items():
            http_jar(
                name = "bc_%s_%s" % (artifact, version.replace(".", "_")),
                sha256 = sha256,
                urls = ["%s/org/bouncycastle/%s-jdk18on/%s/%s-jdk18on-%s.jar" % (_MAVEN, artifact, version, artifact, version)],
            )
    _releases_repo(
        name = "gerrit_releases",
        content = "GERRIT_RELEASES = %r\nBOUNCYCASTLE = %r\n" % (releases, bouncycastle),
    )
    return mctx.extension_metadata(reproducible = True)

gerrit = module_extension(implementation = _gerrit_impl)
