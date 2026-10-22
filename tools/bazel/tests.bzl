"""Test macros: one java_test per test class, against the selected Gerrit series."""

load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_java//java:java_test.bzl", "java_test")

_TEST_DEPS = [
    "//:branch_review_lib",
    "//lib:acceptance_framework",
    "//lib:bouncycastle",
    "//lib:plugin_api_runtime",
]

def _class_name(src):
    path = native.package_name() + "/" + src
    marker = "src/test/java/"
    return path[path.index(marker) + len(marker):-len(".java")].replace("/", ".")

def unit_tests(srcs, support = [], deps = []):
    """Fast tests that need no Gerrit server."""
    _tests(srcs, support, deps, ["unit"], "small", ["-Xmx512m"])

def acceptance_tests(srcs, support = [], deps = []):
    """In-process Gerrit acceptance tests (embedded server, in-process Git transport)."""
    _tests(srcs, support, deps, ["acceptance"], "large", ["-Xmx1g"])

def _tests(srcs, support, deps, tags, size, jvm_flags):
    lib = None
    if support:
        lib = native.package_name().replace("/", "_") + "_support"
        java_library(
            name = lib,
            testonly = True,
            srcs = support,
            deps = _TEST_DEPS + deps,
        )
    for src in [s for s in srcs if s not in support]:
        java_test(
            name = src[:-len(".java")],
            srcs = [src],
            size = size,
            tags = tags,
            jvm_flags = jvm_flags,
            test_class = _class_name(src),
            deps = _TEST_DEPS + deps + ([":" + lib] if lib else []),
        )
