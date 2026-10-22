// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0
//
// Exercises the pinned Release Please library with this repository's release configuration to
// prove the SemVer policy: first release 0.1.0, then fix -> patch, feat -> minor, breaking ->
// minor before 1.0.0 and major from 1.0.0. GitHub is replaced by an in-memory stub; the version
// and changelog logic under test is the library's own.
//
// Run: node --test tools/release/version-policy.test.mjs  (RELEASE_PLEASE_DIR overrides lookup)

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

function releasePleaseDir() {
  if (process.env.RELEASE_PLEASE_DIR) return process.env.RELEASE_PLEASE_DIR;
  const bin = execFileSync("which", ["release-please"]).toString().trim();
  // npm-style layout: <prefix>/node_modules/.bin/release-please -> <prefix>/node_modules/release-please
  const sibling = path.join(path.dirname(bin), "..", "release-please");
  if (fs.existsSync(path.join(sibling, "package.json"))) return fs.realpathSync(sibling);
  let dir = path.dirname(fs.realpathSync(bin));
  while (dir !== path.dirname(dir)) {
    const candidate = path.join(dir, "package.json");
    if (fs.existsSync(candidate) && JSON.parse(fs.readFileSync(candidate)).name === "release-please") {
      return dir;
    }
    dir = path.dirname(dir);
  }
  throw new Error("cannot locate the release-please package");
}

const rpDir = releasePleaseDir();
const require = createRequire(path.join(rpDir, "package.json"));
const { buildStrategy } = require(path.join(rpDir, "build/src/factory.js"));
const { TagName } = require(path.join(rpDir, "build/src/util/tag-name.js"));
const { Version } = require(path.join(rpDir, "build/src/version.js"));
const { parseConventionalCommits } = require(path.join(rpDir, "build/src/commit.js"));
const rpVersion = JSON.parse(fs.readFileSync(path.join(rpDir, "package.json"))).version;

const config = JSON.parse(fs.readFileSync(path.join(root, "release-please-config.json")));
const pkg = config.packages["."];

test("tests run against the pinned release-please version", () => {
  const pinned = fs.readFileSync(path.join(root, ".mise.toml"), "utf8").match(/"npm:release-please" = "([^"]+)"/)[1];
  assert.equal(rpVersion, pinned);
  assert.ok(config.$schema.includes(`/v${pinned}/`), "$schema must reference the pinned version");
});

const github = {
  repository: { owner: "QuentinBtd", repo: "gerrit-branch-review", defaultBranch: "main" },
};

async function strategy() {
  // Same keys Release Please reads from release-please-config.json for the root package.
  return buildStrategy({
    github,
    releaseType: pkg["release-type"] ?? config["release-type"],
    targetBranch: "main",
    path: ".",
    packageName: pkg["package-name"],
    changelogPath: pkg["changelog-path"],
    versionFile: pkg["version-file"],
    bumpMinorPreMajor: config["bump-minor-pre-major"],
    bumpPatchForMinorPreMajor: config["bump-patch-for-minor-pre-major"],
    initialVersion: config["initial-version"],
    includeVInTag: config["include-v-in-tag"],
    includeComponentInTag: config["include-component-in-tag"],
    pullRequestTitlePattern: config["pull-request-title-pattern"],
    changelogSections: config["changelog-sections"],
  });
}

let n = 0;
const commit = (message) => ({ sha: (++n).toString(16).padStart(40, "0"), message, files: ["src/x.java"] });
const release = (v) => ({ tag: new TagName(Version.parse(v), undefined, "-", true), sha: "f".repeat(40), notes: "" });

async function next(latest, ...messages) {
  const s = await strategy();
  // As in Manifest.buildPullRequests: raw commits are parsed before reaching the strategy.
  const commits = parseConventionalCommits(messages.map(commit));
  const pr = await s.buildReleasePullRequest(commits, latest ? release(latest) : undefined);
  assert.ok(pr, "a release PR is proposed");
  return pr;
}

test("first release is exactly 0.1.0", async () => {
  assert.equal((await next(undefined, "feat: initial feature")).version.toString(), "0.1.0");
  assert.equal((await next(undefined, "fix: initial fix")).version.toString(), "0.1.0");
  const manifest = JSON.parse(fs.readFileSync(path.join(root, ".release-please-manifest.json")));
  const version = fs.readFileSync(path.join(root, "version.txt"), "utf8").trim();
  if (Object.keys(manifest).length === 0) {
    assert.equal(version, "0.0.0", "unreleased state: version.txt stays 0.0.0 until the first release");
  }
  assert.equal(pkg["release-as"], undefined, "no permanent release-as override");
});

test("0.1.0 + fix -> 0.1.1", async () => {
  assert.equal((await next("0.1.0", "fix(sync): handle x")).version.toString(), "0.1.1");
});

test("0.1.0 + feat -> 0.2.0", async () => {
  assert.equal((await next("0.1.0", "feat(sync): add y")).version.toString(), "0.2.0");
});

test("0.1.0 + breaking -> 0.2.0 with breaking notes", async () => {
  const pr = await next("0.1.0", "feat(config)!: rename key\n\nBREAKING CHANGE: targetBranch is now target");
  assert.equal(pr.version.toString(), "0.2.0");
  assert.match(pr.body.toString(), /BREAKING CHANGES/);
  assert.match(pr.body.toString(), /targetBranch is now target/);
});

test("1.0.0 + breaking -> 2.0.0", async () => {
  assert.equal((await next("1.0.0", "fix!: drop Gerrit 3.12 support")).version.toString(), "2.0.0");
});

test("release PR title and updated files follow the repository conventions", async () => {
  const pr = await next("0.1.0", "fix: z");
  assert.equal(pr.title.toString(), "chore: release 0.1.1");
  const paths = pr.updates.map((u) => u.path).sort();
  assert.deepEqual(paths, ["CHANGELOG.md", "version.txt"]);
  assert.match(pr.title.toString(), /^chore: /, "release PR title is itself a Conventional Commit");
});
