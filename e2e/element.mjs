// Optional browser check (dev only): a zoomed screenshot of one element.
//   node element.mjs "/function.html?cs=ITS" ".admin-heads-panel .collapsible-header" 390 [name]
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { signIn, open, BASE } from './support.mjs';

const [path, selector, width = '390', name = 'element'] = process.argv.slice(2);
mkdirSync(new URL('./screenshots/', import.meta.url), { recursive: true });
const browser = await chromium.launch({ channel: process.env.E2E_BROWSER || 'msedge' });
const page = await browser.newPage({ viewport: { width: Number(width), height: 900 }, deviceScaleFactor: 2 });
await signIn(page);
await open(page, BASE + path);
await page.locator(selector).first().screenshot({
  path: new URL(`./screenshots/${name}.png`, import.meta.url).pathname.replace(/^\/(\w:)/, '$1')
});
await browser.close();
console.log('element screenshot done');
