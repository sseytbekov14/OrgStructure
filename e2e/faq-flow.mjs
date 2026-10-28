// Optional browser check (dev only): the FAQ panel and the FAQ editor of ITS, with screenshots. Opens the editor,
// adds a question, types an invalid answer, shows the preview, cancels (nothing is saved).
// Output: e2e/screenshots/faq-*.png
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { signIn, open, BASE } from './support.mjs';

const shot = (name) => new URL(`./screenshots/faq-${name}.png`, import.meta.url).pathname.replace(/^\/(\w:)/, '$1');
mkdirSync(new URL('./screenshots/', import.meta.url), { recursive: true });
const browser = await chromium.launch({ channel: process.env.E2E_BROWSER || 'msedge' });
const problems = [];
for (const width of (process.env.E2E_WIDTHS || '1440,390').split(',').map(Number)) {
  const page = await browser.newPage({ viewport: { width, height: 900 } });
  page.on('pageerror', (err) => problems.push(`page error: ${err.message}`));
  page.on('console', (msg) => { if (msg.type() === 'error' && !/Failed to load resource/.test(msg.text())) problems.push(`console: ${msg.text()}`); });
  await signIn(page);
  await open(page, `${BASE}/function.html?cs=ITS`);
  const toggle = page.locator('.faq-toggle');
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click();
  await page.screenshot({ path: shot(`panel-${width}`) });
  await page.click('[data-faq-edit]');
  await page.waitForSelector('.faq-editor', { timeout: 5000 });
  await page.locator('.faq-editor-kind').selectOption('QUESTIONS');
  await page.locator('.faq-editor button', { hasText: 'Add section' }).click();
  const fields = page.locator('.faq-editor-section').last().locator('input, textarea');
  await fields.nth(0).fill('Questions');
  await fields.nth(1).fill('How do I order a laptop?');
  await fields.nth(2).fill('Use https://portal.example and <b>not</b> e-mail.');
  // The editor is a modal dialog: its content scrolls, so the screenshots are of the viewport
  await page.screenshot({ path: shot(`editor-${width}`) });
  await page.locator('.faq-editor .ui-dialog-body').evaluate((body) => { body.scrollTop = 0; });
  await page.screenshot({ path: shot(`editor-top-${width}`) });
  await page.locator('.faq-editor button', { hasText: 'Preview' }).click();
  await page.screenshot({ path: shot(`preview-${width}`) });
  await page.locator('.faq-editor button', { hasText: 'Back to editing' }).click();
  const overflow = await page.evaluate(() => {
    const body = document.querySelector('.faq-editor .ui-dialog-body');
    return Math.max(document.documentElement.scrollWidth - window.innerWidth, body.scrollWidth - body.clientWidth);
  });
  if (overflow > 1) problems.push(`@${width}: horizontal overflow ${overflow}px in the editor`);
  const footer = await page.locator('.faq-editor .ui-dialog-footer').boundingBox();
  if (!footer || footer.y + footer.height > 900) problems.push(`@${width}: the Save bar is not visible`);
  await page.locator('.faq-editor-toolbar button', { hasText: 'Cancel' }).click();
  await page.locator('.ui-confirm button', { hasText: 'Discard' }).click();
  await page.close();
}
await browser.close();
console.log(problems.join('\n'));
console.log(`faq flow done (${problems.length} problem(s))`);
