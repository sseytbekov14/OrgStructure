// Optional browser check (dev only, U6): profile with City / Country and "Counselees", the limited card of an
// external manager, search results without a copy button. Output: e2e/screenshots/cards-*.png
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { signIn, open, BASE } from './support.mjs';

const shot = (name) => new URL(`./screenshots/cards-${name}.png`, import.meta.url).pathname.replace(/^\/(\w:)/, '$1');
mkdirSync(new URL('./screenshots/', import.meta.url), { recursive: true });
const browser = await chromium.launch({ channel: process.env.E2E_BROWSER || 'msedge' });
const problems = [];
for (const width of (process.env.E2E_WIDTHS || '1440,390').split(',').map(Number)) {
  const page = await browser.newPage({ viewport: { width, height: 900 } });
  page.on('pageerror', (err) => problems.push(`page error: ${err.message}`));
  await signIn(page);
  await open(page, `${BASE}/`);
  const person = await page.evaluate(async () => {
    const all = await (await fetch('/api/employees')).json();
    return all.find((e) => e.pmExternal === true) || null;
  });
  if (!person) { problems.push('no employee with an external manager in the data'); break; }
  await page.fill('#searchInput', person.name.split(',')[0]);
  await page.waitForTimeout(900);
  await page.screenshot({ path: shot(`search-${width}`) });
  if (await page.locator('#searchResults [data-copy]').count()) problems.push('copy button in search results');
  await page.locator('#searchResults .result-main').first().click();
  await page.waitForSelector('#profileDialog[open]');
  await page.screenshot({ path: shot(`profile-${width}`) });
  const external = page.locator('#profileDialog [data-external-id]');
  if (await external.count()) {
    await external.first().click();
    await page.waitForSelector('.external-person-dialog');
    await page.screenshot({ path: shot(`external-${width}`) });
  } else {
    problems.push(`no external manager row for ${person.name}`);
  }
  await page.close();
}
await browser.close();
console.log(problems.join('\n'));
console.log(`cards flow done (${problems.length} problem(s))`);
