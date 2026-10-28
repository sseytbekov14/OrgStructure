// Optional browser check (dev only): lists the elements wider than the viewport on a page.
//   node overflow.mjs /admin.html 390
import { chromium } from 'playwright';
import { signIn, open, BASE } from './support.mjs';

const [path = '/', width = '390'] = process.argv.slice(2);
const browser = await chromium.launch({ channel: process.env.E2E_BROWSER || 'msedge' });
const page = await browser.newPage({ viewport: { width: Number(width), height: 900 } });
await signIn(page);
await open(page, BASE + path);
const wide = await page.evaluate(() => [...document.querySelectorAll('body *')]
  .filter((el) => el.getBoundingClientRect().right > window.innerWidth + 1)
  .slice(0, 15)
  .map((el) => `${el.tagName.toLowerCase()}.${[...el.classList].join('.')} right=${Math.round(el.getBoundingClientRect().right)}`));
console.log(wide.join('\n') || 'no overflow');
await browser.close();
