// Optional browser check (dev only): the admin flows of the function page as an admin, with screenshots.
// Opens ITS, expands the heads panel, opens a profile, the edit form, types an invalid value, opens the reset dialog.
// Nothing is saved. Output: e2e/screenshots/flow-*.png
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { signIn, open, BASE } from './support.mjs';

const shot = (name) => new URL(`./screenshots/flow-${name}.png`, import.meta.url).pathname.replace(/^\/(\w:)/, '$1');
mkdirSync(new URL('./screenshots/', import.meta.url), { recursive: true });
const browser = await chromium.launch({ channel: process.env.E2E_BROWSER || 'msedge' });
const problems = [];
for (const width of (process.env.E2E_WIDTHS || '1440,390').split(',').map(Number)) {
  const page = await browser.newPage({ viewport: { width, height: 900 } });
  page.on('pageerror', (err) => problems.push(`page error: ${err.message}`));
  page.on('console', (msg) => { if (msg.type() === 'error' && !/Failed to load resource/.test(msg.text())) problems.push(`console: ${msg.text()}`); });
  await signIn(page);
  await open(page, `${BASE}/function.html?cs=ITS`);
  await page.click('[data-view="groups"]');
  await page.waitForSelector('.admin-heads-panel', { timeout: 5000 });
  const toggle = page.locator('.admin-heads-panel .collapsible-header');
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click();
  await page.screenshot({ path: shot(`heads-${width}`) });
  await page.locator('.emp-card', { hasText: 'Mint' }).first().click();
  await page.waitForSelector('[data-admin-edit]', { timeout: 5000 });
  await page.click('[data-admin-edit]');
  await page.waitForSelector('.admin-edit-dialog', { timeout: 5000 });
  const focusInside = await page.evaluate(() => !!document.activeElement?.closest('.ui-dialog'));
  if (!focusInside) problems.push(`@${width}: focus is not in the edit dialog after opening`);
  await page.fill('.admin-edit-dialog [name=areaOfDuties]', '=SUM(A1) <b>x</b>');
  await page.locator('.admin-edit-dialog [name=areaOfDuties]').blur();
  await page.screenshot({ path: shot(`edit-${width}`) });
  await page.fill('.admin-edit-dialog [name=areaOfDuties]', 'Workplace IT');
  const reset = page.locator('.admin-edit-dialog .admin-remove-btn');
  if (await reset.count()) {
    await reset.click();
    await page.waitForTimeout(300);
    await page.screenshot({ path: shot(`reset-${width}`) });
    await page.locator('.ui-confirm button', { hasText: 'Cancel' }).click();
  }
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  if (overflow > 1) problems.push(`@${width}: horizontal overflow ${overflow}px`);
  await page.close();
}
await browser.close();
console.log(problems.join('\n'));
console.log(`admin flows done (${problems.length} problem(s))`);
