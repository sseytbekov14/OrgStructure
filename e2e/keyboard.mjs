// Optional browser check (dev only, U7): keyboard only. Opens a profile with Enter, the edit dialog with Enter, tabs
// around (focus must stay in the dialog), closes with Escape (focus must come back), opens a group menu with Enter,
// closes it with Escape, and checks that the focused elements show a visible focus ring.
import { chromium } from 'playwright';
import { signIn, open, BASE } from './support.mjs';

const browser = await chromium.launch({ channel: process.env.E2E_BROWSER || 'msedge' });
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const problems = [];
const check = (ok, message) => { if (!ok) problems.push(message); else console.log(`ok   ${message}`); };
await signIn(page);
await open(page, `${BASE}/function.html?cs=ITS`);
await page.click('[data-view="groups"]');

const focusRing = () => page.evaluate(() => {
  const el = document.activeElement;
  const style = getComputedStyle(el);
  return style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0 || style.boxShadow !== 'none';
});

// Reach a card with the Tab key (programmatic focus after a mouse click shows no :focus-visible ring)
await page.locator('[data-view="groups"]').focus();
for (let i = 0; i < 60; i++) {
  await page.keyboard.press('Tab');
  if (await page.evaluate(() => document.activeElement.classList.contains('emp-card'))) break;
}
check(await page.evaluate(() => document.activeElement.classList.contains('emp-card')), 'Tab reaches the cards');
check(await focusRing(), 'a card reached by Tab shows a focus ring');
await page.locator('.emp-card', { hasText: 'Mint' }).first().focus();
await page.keyboard.press('Enter');
await page.waitForSelector('#profileDialog[open]');
check(await page.evaluate(() => !!document.activeElement.closest('#profileDialog')), 'Enter opens the profile and focus moves into it');
await page.locator('[data-admin-edit]').focus();
await page.keyboard.press('Enter');
await page.waitForSelector('.admin-edit-dialog');
let inside = true;
for (let i = 0; i < 25; i++) {
  await page.keyboard.press('Tab');
  inside = inside && await page.evaluate(() => !!document.activeElement.closest('.admin-edit-dialog'));
}
check(inside, 'Tab keeps the focus inside the edit dialog (25 presses)');
for (let i = 0; i < 25; i++) {
  await page.keyboard.press('Shift+Tab');
  inside = inside && await page.evaluate(() => !!document.activeElement.closest('.admin-edit-dialog'));
}
check(inside, 'Shift+Tab keeps the focus inside the edit dialog');
check(await focusRing(), 'the focused control in the dialog shows a focus ring');
await page.keyboard.press('Escape');
await page.waitForTimeout(200);
check(await page.locator('.admin-edit-dialog').count() === 0, 'Escape closes the unchanged edit dialog');
check(await page.evaluate(() => document.activeElement?.hasAttribute('data-admin-edit')), 'focus returns to the Edit button');
await page.keyboard.press('Escape');
await page.waitForTimeout(200);

await page.locator('#adminGroupsToggle').focus();
await page.keyboard.press('Enter');
await page.waitForSelector('.admin-group-menu-btn');
await page.locator('.admin-group-menu-btn').first().focus();
await page.keyboard.press('Enter');
check(await page.evaluate(() => document.activeElement.getAttribute('role') === 'menuitem'), 'Enter opens a group menu and focuses its first item');
await page.keyboard.press('Escape');
check(await page.evaluate(() => document.activeElement.classList.contains('admin-group-menu-btn')), 'Escape closes the menu and returns focus');

await browser.close();
problems.forEach((p) => console.log(`FAIL ${p}`));
console.log(`keyboard walk-through done (${problems.length} problem(s))`);
