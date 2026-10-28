// Optional browser check (dev only, U7): axe-core on the main pages, with dialogs and the editors opened, as the
// signed-in user of E2E_USER. Prints every violation; exit code 1 when a serious or critical one is found.
//   E2E_LABEL (admin | user) names the report: e2e/reports/axe-<label>.json
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { signIn, open, BASE } from './support.mjs';

const label = process.env.E2E_LABEL || 'admin';
const browser = await chromium.launch({ channel: process.env.E2E_BROWSER || 'msedge' });
// axe needs a page of an explicit context
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const page = await context.newPage();
await signIn(page);

const STATES = [
  { name: 'index', path: '/' },
  { name: 'search', path: '/', act: async () => { await page.fill('#searchInput', 'it'); await page.waitForTimeout(800); } },
  { name: 'function-groups', path: '/function.html?cs=ITS', act: async () => { await page.click('[data-view="groups"]'); await page.waitForTimeout(400); } },
  { name: 'function-tree', path: '/function.html?cs=ITS', act: async () => { await page.click('[data-view="tree"]'); await page.waitForTimeout(400); } },
  { name: 'profile', path: '/function.html?cs=ITS', act: async () => {
    await page.click('[data-view="groups"]'); await page.locator('.emp-card').first().click(); await page.waitForTimeout(1200); } },
  { name: 'faq-open', path: '/function.html?cs=ITS', act: async () => {
    const t = page.locator('.faq-toggle'); if ((await t.getAttribute('aria-expanded')) !== 'true') await t.click(); } }
];
if (label === 'admin') {
  STATES.push(
    { name: 'admin-page', path: '/admin.html' },
    { name: 'edit-dialog', path: '/function.html?cs=ITS', act: async () => {
      await page.click('[data-view="groups"]'); await page.locator('.emp-card', { hasText: 'Mint' }).first().click();
      await page.click('[data-admin-edit]'); await page.waitForSelector('.admin-edit-dialog'); } },
    { name: 'manage-groups', path: '/function.html?cs=ITS', act: async () => {
      await page.click('#adminGroupsToggle'); await page.waitForSelector('.admin-group-column'); } },
    { name: 'heads-panel', path: '/function.html?cs=ITS', act: async () => {
      await page.waitForSelector('.admin-heads-panel');
      const h = page.locator('.admin-heads-panel .collapsible-header');
      if ((await h.getAttribute('aria-expanded')) !== 'true') await h.click(); } },
    { name: 'faq-editor', path: '/function.html?cs=ITS', act: async () => {
      await page.click('[data-faq-edit]'); await page.waitForSelector('.faq-editor'); } }
  );
}

const results = [];
let serious = 0;
for (const state of STATES) {
  await open(page, BASE + state.path);
  if (state.act) await state.act();
  const analysis = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  for (const v of analysis.violations) {
    if (v.impact === 'serious' || v.impact === 'critical') serious++;
    results.push({ state: state.name, id: v.id, impact: v.impact, help: v.help,
      nodes: v.nodes.slice(0, 5).map((n) => n.target.join(' ')) });
  }
}
await browser.close();
mkdirSync(new URL('./reports/', import.meta.url), { recursive: true });
writeFileSync(new URL(`./reports/axe-${label}.json`, import.meta.url), JSON.stringify(results, null, 2));
for (const r of results) console.log(`${r.impact.padEnd(8)} ${r.state.padEnd(16)} ${r.id}: ${r.help} -> ${r.nodes.join(' | ')}`);
console.log(`axe ${label}: ${results.length} violation(s), ${serious} serious/critical`);
process.exit(serious ? 1 : 0);
