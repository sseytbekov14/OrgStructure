// The admin page (admin-page.js): the Login name picker lists everybody (once per e-mail, sorted by name, current
// admins marked and not selectable), typing filters it, the keyboard works; Restore of a hidden person asks first
// (Restore / Cancel) and only Restore sends the request.
// Run: node src/test/js/admin-page.test.js
const { context, runner, assertEqual, assertTrue } = require('./support/sandbox');
const { domGlobals, Event, key } = require('./support/mini-dom');

const { test, done } = runner();

const PEOPLE = [
  { id: 1, name: 'Mint, Mike', email: 'mm@kz.example', jobTitle: 'Admin', cs: 'ITS' },
  { id: 2, name: 'Alfa, Amber', email: 'aa@kz.example', jobTitle: 'Analyst', cs: 'Finance' },
  { id: 3, name: 'Alfa, Amber', email: 'AA@kz.example', jobTitle: 'Analyst', cs: 'ITS' },
  { id: 4, name: 'Kilo, Khaki', email: 'kk@kz.example', jobTitle: 'CIO', cs: 'ITS' }
];

async function settle(times = 8) {
  for (let i = 0; i < times; i++) await new Promise((resolve) => setImmediate(resolve));
}

async function adminPage() {
  const requests = [];
  const answers = {
    'GET /api/me/roles': { body: { authenticated: true, you: 'dev', admin: true, superAdmin: true, editingEnabled: true } },
    'GET /api/manage/admins': { body: [{ id: 7, loginName: 'kk@kz.example', note: '', addedBy: 'dev' }] },
    'GET /api/manage/hidden': { body: [{ employeeId: 9, name: 'Golf, Gold', jobTitle: 'Manager', reason: 'left' }] },
    'GET /api/employees': { body: PEOPLE },
    'DELETE /api/manage/employees/9/hide': { status: 204, body: {} }
  };
  const fetch = (url, options = {}) => {
    const method = (options.method || 'GET').toUpperCase();
    const path = url.replace(/\?.*$/, '');
    requests.push({ method, path });
    const { status = 200, body = {} } = answers[`${method} ${path}`] || { status: 404, body: {} };
    return Promise.resolve({ ok: status < 300, status, json: () => Promise.resolve(JSON.parse(JSON.stringify(body))) });
  };
  const globals = domGlobals({ fetch, Headers: class { set() {} } });
  const { document } = globals;
  const el = (tag, attrs = {}, parent = document.body) => {
    const node = document.createElement(tag);
    Object.entries(attrs).forEach(([k, v]) => node.setAttribute(k, v));
    parent.append(node);
    return node;
  };
  el('p', { id: 'adminYou' });
  el('button', { id: 'adminTabHidden', role: 'tab', 'aria-controls': 'adminHidden' });
  el('button', { id: 'adminTabAdmins', role: 'tab', 'aria-controls': 'adminAdmins', hidden: '' });
  el('tbody', { id: 'adminHiddenRows' }, el('section', { id: 'adminHidden' }));
  const admins = el('section', { id: 'adminAdmins', hidden: '' });
  el('tbody', { id: 'adminAdminsRows' }, admins);
  const form = el('form', { id: 'adminAddForm' }, admins);
  const combo = el('div', { id: 'adminLoginNameCombo' }, form);
  el('input', { id: 'adminLoginName', role: 'combobox', 'aria-expanded': 'false' }, combo);
  el('button', { id: 'adminLoginNameToggle', type: 'button' }, combo);
  el('ul', { id: 'adminLoginNameList', role: 'listbox', hidden: '' }, combo);
  el('p', { id: 'adminLoginNameError' }, form);
  const ctx = context(['ui.js', 'common.js', 'admin-page.js'], globals);
  await settle();
  const input = document.getElementById('adminLoginName');
  const list = document.getElementById('adminLoginNameList');
  return { ctx, document, requests, input, list, options: () => [...list.querySelectorAll('[role=option]')] };
}

const names = (options) => options.map((o) => o.querySelector('.combo-option-name').textContent);

test('a click on Login name lists everybody once, by name; current admins are marked and not selectable', async () => {
  const { input, list, options } = await adminPage();
  assertTrue(list.hidden, 'closed at first');
  input.dispatchEvent(new Event('click'));
  await settle();
  assertTrue(!list.hidden && input.getAttribute('aria-expanded') === 'true', 'open');
  assertEqual(names(options()), ['Alfa, Amber', 'Kilo, Khaki', 'Mint, Mike']);
  const kilo = options()[1];
  assertEqual(kilo.getAttribute('aria-disabled'), 'true');
  assertTrue(kilo.textContent.includes('Admin'), 'marked as admin');
  kilo.dispatchEvent(new Event('click'));
  assertEqual(input.value, '', 'an admin cannot be chosen again');
  options()[2].dispatchEvent(new Event('click'));
  assertEqual(input.value, 'mm@kz.example', 'the e-mail is filled in');
  assertTrue(list.hidden, 'closed after choosing');
});

test('typing filters by name or e-mail; arrows and Enter choose; ESC closes', async () => {
  const { input, list, options } = await adminPage();
  input.value = 'alf';
  input.dispatchEvent(new Event('input'));
  await settle();
  assertEqual(names(options()), ['Alfa, Amber']);
  input.value = 'mm@';
  input.dispatchEvent(new Event('input'));
  await settle();
  assertEqual(names(options()), ['Mint, Mike']);
  key(input, 'ArrowDown');
  await settle();
  assertEqual(options()[0].getAttribute('aria-selected'), 'true');
  assertEqual(input.getAttribute('aria-activedescendant'), options()[0].id);
  const enter = key(input, 'Enter');
  assertTrue(enter.defaultPrevented, 'Enter picks instead of submitting');
  assertEqual(input.value, 'mm@kz.example');
  input.value = 'nobody-here';
  input.dispatchEvent(new Event('input'));
  await settle();
  assertEqual(options().length, 0);
  assertTrue(list.textContent.includes('You can still add the typed name'), 'free text stays possible');
  key(input, 'Escape');
  assertTrue(list.hidden, 'ESC closes');
  assertEqual(input.value, 'nobody-here', 'typed text kept');
});

test('Restore asks first: Cancel sends nothing, Restore restores', async () => {
  const { document, requests } = await adminPage();
  const restore = () => [...document.querySelectorAll('#adminHiddenRows button')].find((b) => b.textContent === 'Restore');
  const top = () => [...document.querySelectorAll('[role=dialog]')].pop();
  const writes = () => requests.filter((r) => r.method !== 'GET');
  restore().click();
  await settle();
  assertEqual(top().querySelector('.ui-dialog-title').textContent, 'Restore Golf, Gold?');
  assertEqual([...top().querySelectorAll('.ui-dialog-footer button')].map((b) => b.textContent), ['Cancel', 'Restore']);
  [...top().querySelectorAll('button')].find((b) => b.textContent === 'Cancel').click();
  await settle();
  assertEqual(writes().length, 0, 'nothing restored on Cancel');
  restore().click();
  await settle();
  [...top().querySelectorAll('button')].find((b) => b.textContent === 'Restore').click();
  await settle();
  assertEqual(writes().map((r) => `${r.method} ${r.path}`), ['DELETE /api/manage/employees/9/hide']);
});

done('admin page check');
