// Optional browser check (dev only): screenshots of the main pages at 1440, 1024 and 390 px against a running app.
// Uses the locally installed Edge (or Chrome), no browser download.
//   E2E_BASE_URL (default http://localhost:8080), E2E_USER / E2E_PASSWORD (password form; empty: open site),
//   E2E_BROWSER (msedge | chrome, default msedge), E2E_PAGES (comma list, default index,function-tree,function-groups,admin)
// Output: e2e/screenshots/<page>-<width>.png
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { signIn, open, BASE } from './support.mjs';

const WIDTHS = [1440, 1024, 390];
const PAGES = {
  index: { path: '/' },
  'function-tree': { path: '/function.html?cs=ITS', view: 'tree' },
  'function-groups': { path: '/function.html?cs=ITS', view: 'groups' },
  'function-people': { path: '/function.html?cs=People', view: 'groups' },
  admin: { path: '/admin.html' },
  'signed-out': { path: '/signed-out.html' }
};
const wanted = (process.env.E2E_PAGES || 'index,function-tree,function-groups,admin,signed-out').split(',');

mkdirSync(new URL('./screenshots/', import.meta.url), { recursive: true });
const browser = await chromium.launch({ channel: process.env.E2E_BROWSER || 'msedge' });
const context = await browser.newContext();
const page = await context.newPage();
const problems = [];
page.on('console', (msg) => { if (msg.type() === 'error' && !/Failed to load resource/.test(msg.text())) problems.push(`console: ${msg.text()}`); });
page.on('response', (res) => { if (res.status() >= 400) problems.push(`HTTP ${res.status()} ${new URL(res.url()).pathname}`); });
page.on('pageerror', (err) => problems.push(`page error: ${err.message}`));
await signIn(page);

for (const name of wanted) {
  const target = PAGES[name];
  if (!target) continue;
  for (const width of WIDTHS) {
    await page.setViewportSize({ width, height: 900 });
    await open(page, BASE + target.path);
    if (target.view) {
      await page.click(`[data-view="${target.view}"]`).catch(() => {});
      await page.waitForTimeout(400);
    }
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (overflow > 1 && !(target.view === 'tree')) problems.push(`${name} @${width}: horizontal overflow ${overflow}px`);
    await page.screenshot({ path: new URL(`./screenshots/${name}-${width}.png`, import.meta.url).pathname.replace(/^\/(\w:)/, '$1'), fullPage: false });
  }
}
await browser.close();
if (problems.length) {
  console.log(problems.join('\n'));
}
console.log(`screenshots done (${problems.length} problem(s))`);
