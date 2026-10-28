// U3: the Function heads panel: collapsible (collapsed by default, aria-expanded, remembered in sessionStorage), one
// main head and additional heads, Make main (swap), reorder, remove, add (function members first), save order,
// warning without head; and the public page: main head primary, additional heads below in Groups and Tree.
// Run: node src/test/js/admin-heads-panel.test.js
const { context, runner, assertEqual, assertTrue } = require('./support/sandbox');
const { adminPage, settle } = require('./support/admin-page');

const { test, done } = runner();

const HEADS = { cs: 'ITS', heads: [
  { employeeId: 1, name: 'Kilo, Khaki', jobTitle: 'Chief Information Officer', countryCode: 'KZ', rank: 1 },
  { employeeId: 2, name: 'Lima, Lemon', jobTitle: 'IT Operations Manager', countryCode: 'KZ', rank: 2 },
  { employeeId: 3, name: 'Oscar, Olive', jobTitle: 'IT Lead', countryCode: 'UZ', rank: 3 }
], hiddenHeads: 0 };
const MEMBERS = [
  { id: 4, name: 'Mint, Mike', email: 'mike@kz.example', jobTitle: 'System Administrator', countryCode: 'KZ', group: 'Service Desk' },
  { id: 5, name: 'Navy, November', email: 'navy@kz.example', jobTitle: 'IT Project Manager', countryCode: 'KZ', group: 'IT PMO' }
];

async function panel(heads = HEADS) {
  const page = adminPage({
    'GET /api/manage/functions/ITS/heads': { body: heads },
    'PUT /api/manage/functions/ITS/heads': (request) => ({ status: 200, body: { cs: 'ITS', heads: [], hiddenHeads: 0 } })
  });
  page.ctx.sandbox.members = MEMBERS;
  await page.ctx.run(`window.onFunctionRendered('ITS', members)`);
  await settle();
  const section = () => page.document.getElementById('adminHeads');
  const names = () => [...section().querySelectorAll('.admin-heads-name')].map((n) => n.textContent);
  return { ...page, section, names, header: () => section().querySelector('.collapsible-header') };
}

test('collapsed by default; the header toggles it and the state is remembered in sessionStorage', async () => {
  const { header, section, globals, ctx } = await panel();
  assertEqual(header().getAttribute('aria-expanded'), 'false', 'collapsed');
  assertTrue(section().querySelector('.collapsible-body').hidden, 'body hidden');
  header().click();
  assertEqual(header().getAttribute('aria-expanded'), 'true');
  assertEqual(globals.sessionStorage.getItem('orgchart-admin-heads-open'), 'true', 'remembered');
  ctx.run('renderAdminHeads()');
  assertEqual(header().getAttribute('aria-expanded'), 'true', 'still open after a re-render');
});

test('one main head slot, additional heads as rows; only name, job title and country code', async () => {
  const { section } = await panel();
  const main = section().querySelector('.admin-heads-main');
  assertTrue(main.textContent.includes('Main head') && main.textContent.includes('Kilo, Khaki'), 'main head');
  assertEqual(section().querySelectorAll('.admin-heads-main').length, 1, 'exactly one main head');
  const rows = section().querySelectorAll('.admin-heads-item');
  assertEqual(rows.length, 2, 'two additional heads');
  const person = rows[1].querySelector('.admin-heads-person');
  assertEqual([...person.children].map((c) => c.textContent), ['Oscar, Olive', 'IT Lead', 'UZ']);
  assertTrue(section().querySelector('.badge').textContent === 'Admin', 'admin badge');
});

test('Make main swaps: the chosen head becomes rank 1, the former main head the first additional one', async () => {
  const { section, names, button } = await panel();
  button(section(), 'Make main');
  const makeMain = section().querySelectorAll('.admin-heads-item')[1].querySelector('button');
  assertEqual(makeMain.textContent, 'Make main');
  makeMain.click();
  assertEqual(names(), ['Oscar, Olive', 'Kilo, Khaki', 'Lima, Lemon']);
  assertEqual(section().querySelectorAll('.admin-heads-main').length, 1, 'still one main head');
  assertTrue(section().textContent.includes('Not saved'), 'marked as not saved');
});

test('reorder within the additional heads; the first one cannot move above the main head', async () => {
  const { section, names } = await panel();
  const firstRow = section().querySelectorAll('.admin-heads-item')[0];
  const up = [...firstRow.querySelectorAll('button')].find((b) => b.textContent === '↑');
  assertTrue(up.disabled, 'up disabled for the first additional head');
  [...firstRow.querySelectorAll('button')].find((b) => b.textContent === '↓').click();
  assertEqual(names(), ['Kilo, Khaki', 'Oscar, Olive', 'Lima, Lemon']);
});

test('remove and add (function members first), then Save sends the order and the expected heads', async () => {
  const { section, names, ctx, writes, type } = await panel();
  [...section().querySelectorAll('.admin-heads-item')[0].querySelectorAll('button')].find((b) => b.textContent === '✕').click();
  assertEqual(names(), ['Kilo, Khaki', 'Oscar, Olive']);
  const search = section().querySelector('.admin-heads-search');
  type(search, 'mint');
  await settle();
  const candidate = section().querySelector('.admin-heads-candidate');
  assertTrue(candidate.textContent.includes('Mint, Mike') && candidate.textContent.includes('Service Desk'), 'member with group');
  candidate.click();
  assertEqual(names(), ['Kilo, Khaki', 'Oscar, Olive', 'Mint, Mike']);
  const save = [...section().querySelectorAll('button')].find((b) => b.textContent === 'Save heads');
  assertTrue(!save.disabled, 'save enabled');
  save.click();
  await settle();
  assertEqual(writes()[0].body, { employeeIds: [1, 3, 4], expectedHeads: [1, 2, 3], confirmRemoveAllHeads: false });
  assertTrue(ctx.run('refreshes') === 1, 'refreshed after save');
});

test('without heads: warning, and removing all asks before saving', async () => {
  const { section, topDialog, button, writes } = await panel({ cs: 'ITS', heads: [HEADS.heads[0]], hiddenHeads: 0 });
  section().querySelector('.admin-heads-main button').click();
  assertTrue(section().querySelector('.admin-warning').textContent.includes('no active head'), 'warning');
  assertTrue(section().textContent.includes('No head'), 'badge in the header');
  [...section().querySelectorAll('button')].find((b) => b.textContent === 'Save heads').click();
  await settle();
  assertTrue(topDialog().querySelector('.ui-dialog-title').textContent.includes('without a head'), 'asked');
  button(topDialog(), 'Save without head').click();
  await settle();
  assertEqual(writes()[0].body.confirmRemoveAllHeads, true);
});

test('409 HEADS_CHANGED is explained in the panel', async () => {
  const { section, answers } = await panel();
  answers['PUT /api/manage/functions/ITS/heads'] = { status: 409, body: { code: 'HEADS_CHANGED', message: 'changed' } };
  section().querySelectorAll('.admin-heads-item')[1].querySelector('button').click();
  [...section().querySelectorAll('button')].find((b) => b.textContent === 'Save heads').click();
  await settle();
  assertTrue(section().querySelector('.admin-status').textContent.includes('Changed by someone else'), 'message');
});

// --- public function page -------------------------------------------------------------------------------------

const fn = context(['common.js', 'function.js']);
const ROWS = [
  { id: 1, cs: 'ITS', name: 'Kilo, Khaki', group: 'HEAD', jobTitle: 'CIO', countryCode: 'KZ' },
  { id: 2, cs: 'ITS', name: 'Lima, Lemon', group: 'HEAD', jobTitle: 'IT Operations Manager', countryCode: 'KZ', parentId: 9 },
  { id: 3, cs: 'ITS', name: 'Mint, Mike', group: 'Service Desk', jobTitle: 'Admin', countryCode: 'KZ', parentId: 2 },
  { id: 9, cs: 'ITS', name: 'Other, Boss', group: 'IT PMO', jobTitle: 'PM', countryCode: 'KZ', parentId: 1 }
].map((e, i) => ({ ...e, _id: i }));

test('groups view: the main head is primary, the additional heads follow below it', () => {
  const html = String(fn.run(`renderGroupsView(${JSON.stringify(ROWS)})`)).replace(/\s+/g, ' ');
  const main = html.indexOf('head-card head-card--main');
  const additional = html.indexOf('head-cards--additional');
  assertTrue(main > 0 && additional > main, 'main first, then the additional row');
  assertTrue(html.slice(main, additional).includes('Kilo, Khaki'), 'main head');
  assertTrue(html.slice(additional).includes('head-card head-card--additional') && html.slice(additional).includes('Lima, Lemon'), 'additional head');
});

test('groups view with one head: no additional row', () => {
  const html = String(fn.run(`renderGroupsView(${JSON.stringify(ROWS.filter((e) => e.id !== 2))})`));
  assertTrue(!html.includes('head-cards--additional'), 'no additional row');
});

test('tree: the additional heads hang directly below the main head (also when AD says otherwise)', () => {
  fn.run(`employeeDirectory = ${JSON.stringify(ROWS)}; buildTree(employeeDirectory);`);
  const result = JSON.parse(fn.run(`JSON.stringify({ root: tree.root.emp.name,
    children: tree.root.children.map((c) => [c.emp.name, !!c.isAdditionalHead]) })`));
  assertEqual(result.root, 'Kilo, Khaki');
  assertEqual(result.children[0], ['Lima, Lemon', true], 'additional head first below the main head');
  const card = String(fn.run(`renderEmpCard(${JSON.stringify(ROWS[1])}, { additionalHead: true })`));
  assertTrue(card.includes('emp-card--additional-head') && card.includes('>Head<'), 'marked as head');
});

done('admin heads panel check');
