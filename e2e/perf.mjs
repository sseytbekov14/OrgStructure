// Optional browser check (dev only, U7): rendering times with synthetic data of the expected size: 217 people in
// 8 functions, the largest (ITS) with 50. /api/employees is answered by the test (page.route), everything else by
// the running app. Prints median times of 5 runs for the Tree and Groups views of ITS and the start page search.
import { chromium } from 'playwright';
import { signIn, open, BASE } from './support.mjs';

const SIZES = { ITS: 50, Finance: 35, People: 30, 'F&O': 28, Markets: 22, OGC: 18, QRM: 17, Tenders: 17 };
function synthetic() {
  const rows = [];
  let id = 1;
  for (const [cs, size] of Object.entries(SIZES)) {
    const first = id;
    for (let i = 0; i < size; i++, id++) {
      const manager = i === 0 ? null : first + Math.floor((i - 1) / 4);
      rows.push({ id, cs, name: `Person ${id}, ${cs}`, email: `p${id}@kz.example`, jobTitle: i === 0 ? 'Head of function' : `Specialist ${i}`,
        group: i < 2 ? 'HEAD' : `Team ${1 + (i % 5)}`, parentId: manager, pmId: manager, pmExternal: false,
        pm: manager ? `Person ${manager}, ${cs}` : null, photoUrl: '/photos/default.jpg', gradeOrder: i === 0 ? 1 : 5,
        countryCode: 'KZ', city: 'Almaty', country: 'Kazakhstan', groupOrder: 10 * (1 + (i % 5)),
        areaOfDuties: 'Duties '.repeat(10), jobDescription: 'Description '.repeat(10) });
    }
  }
  return rows;
}
const median = (values) => values.sort((a, b) => a - b)[Math.floor(values.length / 2)];

const data = JSON.stringify(synthetic());
const browser = await chromium.launch({ channel: process.env.E2E_BROWSER || 'msedge' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await signIn(page);
await page.route('**/api/employees**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: data }));
await page.route('**/api/search**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: data }));

const results = {};
for (const view of ['tree', 'groups']) {
  await open(page, `${BASE}/function.html?cs=ITS`);
  await page.click(`[data-view="${view}"]`);
  const times = [];
  for (let i = 0; i < 5; i++) {
    times.push(await page.evaluate(async () => {
      const start = performance.now();
      await window.refreshOrgChart();
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
      return performance.now() - start;
    }));
  }
  results[`ITS ${view} (50 people), refresh + layout`] = median(times);
}
const loads = [];
for (let i = 0; i < 3; i++) {
  const start = Date.now();
  await page.goto(`${BASE}/function.html?cs=ITS`, { waitUntil: 'load' });
  await page.waitForSelector('#orgChart .emp-card, #orgChart .head-card', { timeout: 10000 });
  loads.push(Date.now() - start);
}
results['ITS page load to first cards'] = median(loads);
await open(page, `${BASE}/`);
const searches = [];
for (let i = 0; i < 3; i++) {
  await page.fill('#searchInput', '');
  const start = Date.now();
  await page.fill('#searchInput', 'person');
  await page.waitForSelector('#searchResults .result-row', { timeout: 10000 });
  searches.push(Date.now() - start);
}
results['start page search, 217 results (incl. 150 ms debounce)'] = median(searches);
await browser.close();
for (const [k, v] of Object.entries(results)) console.log(`${k}: ${Math.round(v)} ms`);
