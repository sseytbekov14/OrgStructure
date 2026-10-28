// Shared helpers of the optional browser checks.
export const BASE = process.env.E2E_BASE_URL || 'http://localhost:8080';

/** Signs in with the password form when E2E_USER is set (otherwise the site is expected to be open) */
export async function signIn(page, user = process.env.E2E_USER, password = process.env.E2E_PASSWORD) {
  if (!user) return;
  await page.goto(`${BASE}/login`);
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', password || '');
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.click('button[type="submit"]')]);
}

/** The pages keep an SSE stream open (no network idle): wait for load and a little rendering time */
export async function open(page, url) {
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForTimeout(700);
}
