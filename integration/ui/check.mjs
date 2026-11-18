// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

// Browser check of the change-page "Source branch" row (display, gitiles link, copy button,
// removal on an ordinary Change), run inside the pinned Playwright image by
// integration/blackbox.py. Exits non-zero with a message on the first failed expectation.
//
// Usage: node check.mjs BASE_URL PROJECT BRANCH_CHANGE PLAIN_CHANGE PLAIN_SUBJECT BRANCH PLAYWRIGHT_VERSION
import { createRequire } from 'node:module';
import { chromium } from 'playwright-core';

const [base, project, branchChange, plainChange, plainSubject, branch, expectedVersion] =
  process.argv.slice(2);
const { version } = createRequire(import.meta.url)('playwright-core/package.json');
if (version !== expectedVersion) {
  console.error(`playwright-core ${version} does not match the image ${expectedVersion}`);
  process.exit(1);
}

const browser = await chromium.launch();
try {
  const context = await browser.newContext();
  const page = await context.newPage();
  const row = page.locator('branch-review-source-branch .value');
  const changeUrl = (n) => `${base}/c/${project}/+/${n}`;

  await page.goto(changeUrl(branchChange));
  await row.waitFor({ timeout: 60_000 });
  const shown = await row.textContent();
  if (shown !== branch) throw new Error(`row shows ${JSON.stringify(shown)}, expected ${branch}`);

  // The name links to the branch in gitiles (bundled with the official image).
  const link = row.locator('a');
  const href = await link.evaluate((a) => a.href);
  const expected = `/plugins/gitiles/${project}/+/refs/heads/${branch}`;
  if (!href.endsWith(expected)) throw new Error(`link is ${href}, expected ...${expected}`);
  const browse = await page.request.get(href);
  if (!browse.ok()) throw new Error(`gitiles link answered ${browse.status()}`);

  // Gerrit's copy button next to the name copies the short branch name.
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], {
    origin: new URL(base).origin,
  });
  await row.locator('gr-copy-clipboard gr-button').click();
  const readClipboard = () => page.evaluate(() => navigator.clipboard.readText());
  for (let i = 0; i < 50 && (await readClipboard()) !== branch; i++) await page.waitForTimeout(100);
  const copied = await readClipboard();
  if (copied !== branch) throw new Error(`copied ${JSON.stringify(copied)}, expected ${branch}`);

  // Follow a link inside the app: the router reuses the change view, which is where a stale row
  // from the previous Change would survive. The marker proves no full page load happened.
  await page.evaluate((url) => {
    window.__branchReviewMarker = true;
    const a = document.createElement('a');
    a.href = url;
    document.body.append(a);
    a.click();
  }, changeUrl(plainChange));
  await page.waitForURL(`**/+/${plainChange}`);
  await page.getByText(plainSubject).first().waitFor({ timeout: 60_000 });
  if (!(await page.evaluate(() => window.__branchReviewMarker))) {
    throw new Error('navigation reloaded the page; the stale-row case was not exercised');
  }
  await row.waitFor({ state: 'detached', timeout: 15_000 });
} finally {
  await browser.close();
}
