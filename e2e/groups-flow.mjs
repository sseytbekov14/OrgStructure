// Optional browser check (dev only): the groups management of ITS and the hide dialog, with screenshots.
// Opens manage mode, a group menu, the move dialog and the hide dialog; nothing is saved.
// Output: e2e/screenshots/groups-*.png
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { signIn, open, BASE } from './support.mjs';

const shot = (name) => new URL(`./screenshots/groups-${name}.png`, import.meta.url).pathname.replace(/^\/(\w:)/, '$1');
mkdirSync(new URL('./screenshots/', import.meta.url), { recursive: true });
const browser = await chromium.launch({ channel: process.env.E2E_BROWSER || 'msedge' });
const problems = [];
for (const width of (process.env.E2E_WIDTHS || '1440,390').split(',').map(Number)) {
  const page = await browser.newPage({ viewport: { width, height: 900 } });
  page.on('pageerror', (err) => problems.push(`page error: ${err.message}`));
  page.on('console', (msg) => { if (msg.type() === 'error' && !/Failed to load resource/.test(msg.text())) problems.push(`console: ${msg.text()}`); });
  await signIn(page);
  await open(page, `${BASE}/function.html?cs=ITS`);
  await page.click('[data-view="tree"]');
  await page.waitForTimeout(400);
  await page.screenshot({ path: shot(`tree-${width}`) });
  await page.click('#adminGroupsToggle');
  await page.waitForSelector('.admin-group-column', { timeout: 5000 });
  await page.screenshot({ path: shot(`manage-${width}`), fullPage: true });
  await page.locator('.admin-group-menu-btn').first().click();
  await page.waitForTimeout(150);
  await page.screenshot({ path: shot(`menu-${width}`) });
  await page.keyboard.press('Escape');
  await page.locator('.admin-group-member button', { hasText: 'Move to group' }).first().click();
  await page.waitForSelector('.ui-dialog', { timeout: 5000 });
  await page.screenshot({ path: shot(`move-${width}`) });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(150);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  if (overflow > 1) problems.push(`@${width}: horizontal overflow ${overflow}px in manage mode`);
  await page.click('#adminGroupsToggle');
  await page.click('[data-view="groups"]');
  await page.waitForTimeout(300);
  await page.locator('.emp-card').first().click();
  await page.waitForSelector('[data-admin-hide]', { timeout: 5000 });
  await page.click('[data-admin-hide]');
  await page.waitForSelector('.ui-dialog', { timeout: 5000 });
  await page.screenshot({ path: shot(`hide-${width}`) });
  await page.keyboard.press('Escape');
  await page.close();
}
const admin = await browser.newPage({ viewport: { width: 1024, height: 800 } });
await signIn(admin);
await open(admin, `${BASE}/admin.html`);
await admin.screenshot({ path: shot('admin-hidden-1024') });
await browser.close();
console.log(problems.join('\n'));
console.log(`groups flow done (${problems.length} problem(s))`);
