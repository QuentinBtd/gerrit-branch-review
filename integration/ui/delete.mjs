// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

// Browser check of the "Delete source branch" change action on a closed Change, run inside the
// pinned Playwright image by integration/blackbox.py: signed in as admin, the action is in the
// change's overflow menu, asks for confirmation, deletes the branch and reloads the Change.
// Exits non-zero with a message on the first failed expectation.
//
// Usage: node delete.mjs BASE_URL PROJECT CHANGE BRANCH PLAYWRIGHT_VERSION
import { createRequire } from 'node:module';
import { chromium } from 'playwright-core';

const [base, project, change, branch, expectedVersion] = process.argv.slice(2);
const { version } = createRequire(import.meta.url)('playwright-core/package.json');
if (version !== expectedVersion) {
  console.error(`playwright-core ${version} does not match the image ${expectedVersion}`);
  process.exit(1);
}

const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  // Development sign-in; its redirect goes to the canonical URL, unreachable from here.
  await page.goto(`${base}/login/?user_name=admin`).catch(() => {});
  await page.goto(`${base}/c/${project}/+/${change}`);

  let asked = '';
  page.on('dialog', (d) => {
    asked = d.message();
    d.accept();
  });
  await page.locator('gr-change-actions #moreActions').click();
  // 3.12 also renders a hidden link with the same text in the menu.
  await page.getByText('Delete source branch', { exact: true }).filter({ visible: true }).click();
  await page.getByText(`Deleted source branch ${branch}`).first().waitFor({ timeout: 30_000 });
  if (!asked.includes(branch)) throw new Error(`confirmation was ${JSON.stringify(asked)}`);

  const ref = encodeURIComponent(`refs/heads/${branch}`);
  const gone = await page.request.get(
    `${base}/projects/${encodeURIComponent(project)}/branches/${ref}`,
  );
  if (gone.status() !== 404) throw new Error(`branch still answers ${gone.status()}`);
  // The Change was reloaded: the name is no longer a link to the deleted branch.
  await page
    .locator('branch-review-source-branch .value a')
    .waitFor({ state: 'detached', timeout: 15_000 });
} finally {
  await browser.close();
}
