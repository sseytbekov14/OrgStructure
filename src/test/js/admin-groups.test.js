// U4: the Groups management of the function page (manage mode, menus, moves with Undo, remove, new group, add
// people) and "Hide from site"; group names from admins stay text everywhere.
// Run: node src/test/js/admin-groups.test.js
const { context, runner, assertEqual, assertTrue } = require('./support/sandbox');
const { adminPage, settle } = require('./support/admin-page');
const { key } = require('./support/mini-dom');

const { test, done } = runner();
const PAYLOAD = '<img src=x onerror=alert(1)>';

function groupsAnswer(extra = {}) {
  return {
    cs: 'ITS',
    groups: [
      { id: 1, cs: 'ITS', name: 'IT Operations', kind: 'TEAM', source: 'AUTO', sortOrder: 10, active: true, version: 0,
        memberCount: 1, members: [{ employeeId: 11, name: 'Romeo, Ruby', jobTitle: 'Network Engineer', countryCode: 'KZ', source: 'AD' }] },
      { id: 2, cs: 'ITS', name: 'IT PMO', kind: 'TEAM', source: 'AUTO', sortOrder: 20, active: true, version: 3,
        memberCount: 1, members: [{ employeeId: 12, name: 'Papa, Peach', jobTitle: 'Specialist', countryCode: 'AM', source: 'MANUAL' }] },
      { id: 3, cs: 'ITS', name: 'Empty team', kind: 'TEAM', source: 'MANUAL', sortOrder: 30, active: true, version: 1,
        memberCount: 0, members: [] }
    ],
    heads: [{ employeeId: 1, name: 'Kilo, Khaki' }],
    ungrouped: [{ employeeId: 13, name: 'Mint, Mike', jobTitle: 'Admin', countryCode: 'KZ' }],
    ...extra
  };
}

async function managePage(routes = {}) {
  const page = adminPage({
    'GET /api/manage/functions/ITS/heads': { body: { cs: 'ITS', heads: [], hiddenHeads: 0 } },
    'GET /api/manage/functions/ITS/groups': { body: groupsAnswer() },
    ...routes
  });
  page.ctx.sandbox.members = [
    { id: 11, name: 'Romeo, Ruby', email: 'ruby@kz.example', jobTitle: 'Network Engineer', group: 'IT Operations' },
    { id: 13, name: 'Mint, Mike', email: 'mike@kz.example', jobTitle: 'Admin', group: null }
  ];
  await page.ctx.run(`window.onFunctionRendered('ITS', members)`);
  await settle();
  const toggle = page.document.getElementById('adminGroupsToggle');
  toggle.click();
  await settle();
  const manage = () => page.document.getElementById('adminGroupsManage');
  const column = (name) => [...manage().querySelectorAll('.admin-group-column')]
    .find((c) => c.querySelector('.admin-group-name')?.textContent === name);
  return { ...page, toggle, manage, column };
}

test('the toggle switches to manage mode: every group (also empty ones), people without a group, a New group tile', async () => {
  const { toggle, manage, document, column } = await managePage();
  assertEqual(toggle.getAttribute('aria-pressed'), 'true');
  assertTrue(document.getElementById('orgChart').hidden, 'chart hidden while managing');
  assertEqual([...manage().querySelectorAll('.admin-group-name')].map((n) => n.textContent),
    ['IT Operations', 'IT PMO', 'Empty team', 'Without a group']);
  assertTrue(column('Empty team').textContent.includes('No people yet.'), 'empty group shown');
  assertTrue(manage().querySelector('.admin-group-new').textContent.includes('New group'), 'new group tile');
  toggle.click();
  await settle();
  assertTrue(!document.getElementById('adminGroupsManage'), 'back to the chart');
  assertTrue(!document.getElementById('orgChart').hidden);
});

test('menu: rename disabled for AD groups, delete only for empty groups, move left/right at the ends', async () => {
  const { column, button } = await managePage();
  const items = (name) => Object.fromEntries([...column(name).querySelectorAll('[role=menuitem]')]
    .map((b) => [b.textContent, b.disabled]));
  assertEqual(items('IT Operations'), { Rename: true, 'Move left': true, 'Move right': false, Delete: true });
  assertEqual(items('Empty team'), { Rename: false, 'Move left': false, 'Move right': true, Delete: false });
  const menuButton = column('IT PMO').querySelector('.admin-group-menu-btn');
  menuButton.click();
  assertEqual(menuButton.getAttribute('aria-expanded'), 'true');
  const menu = column('IT PMO').querySelector('[role=menu]');
  assertTrue(!menu.hidden, 'menu open');
  key(menu, 'Escape');
  assertTrue(menu.hidden, 'ESC closes the menu');
  assertTrue(button !== null);
});

test('move to group: POST with the person, toast with Undo, Undo puts them back where they were', async () => {
  const page = await managePage({
    'POST /api/manage/groups/2/members': { body: { groupId: 2, moved: [{ employeeId: 11, previousGroupId: null, previousGroupName: null }] } },
    'DELETE /api/manage/groups/2/members/11': { status: 204, body: {} }
  });
  const { column, button, topDialog, writes, document } = page;
  button(column('IT Operations'), 'Move to group…').click();
  await settle();
  const dialog = topDialog();
  assertEqual(dialog.querySelector('.ui-dialog-title').textContent, 'Move Romeo, Ruby');
  const radios = [...dialog.querySelectorAll('input[type=radio]')];
  assertEqual(radios.length, 2, 'the other active groups');
  button(dialog, 'Move').click();
  await settle();
  assertEqual(writes()[0].path, '/api/manage/groups/2/members');
  assertEqual(writes()[0].body, { employeeIds: [11] });
  const undo = document.querySelector('.ui-toast-action');
  assertEqual(undo.textContent, 'Undo');
  undo.click();
  await settle();
  assertEqual(writes()[1].method, 'DELETE', 'back to the AD default group');
  assertEqual(writes()[1].path, '/api/manage/groups/2/members/11');
});

test('remove from group: disabled for people who belong there by AD; a manual member goes back with Undo offered', async () => {
  const { column, button, writes, document } = await managePage({
    'DELETE /api/manage/groups/2/members/12': { status: 204, body: {} }
  });
  const byAd = button(column('IT Operations'), 'Remove from group');
  assertTrue(byAd.disabled, 'AD member cannot be removed');
  assertTrue(byAd.title.includes('Belongs here by Active Directory'), 'explained');
  button(column('IT PMO'), 'Remove from group').click();
  await settle();
  assertEqual(writes()[0].method, 'DELETE');
  assertTrue(document.querySelector('.ui-toast-action'), 'Undo offered');
});

test('new group: name validated with the shared rules, server field errors shown, success reloads', async () => {
  const page = await managePage({
    'POST /api/manage/functions/ITS/groups': [
      { status: 409, body: { code: 'GROUP_EXISTS', message: 'There is already a group named IT PMO in ITS.' } },
      { status: 201, body: { id: 9 } }]
  });
  const { manage, topDialog, button, type, writes } = page;
  manage().querySelector('.admin-group-new').click();
  await settle();
  const dialog = topDialog();
  const input = dialog.querySelector('[name=name]');
  type(input, 'x');
  button(dialog, 'Create').click();
  await settle();
  assertEqual(writes().length, 0, 'invalid: no request');
  assertTrue(dialog.querySelector('.field-error').textContent.includes('at least 2'), 'inline error');
  type(input, 'IT PMO');
  button(dialog, 'Create').click();
  await settle();
  assertTrue(dialog.isConnected, 'kept open on 409');
  type(input, 'Platform');
  button(dialog, 'Create').click();
  await settle();
  assertEqual(writes()[1].body, { name: 'Platform', kind: 'TEAM' });
  assertTrue(!dialog.isConnected, 'closed after success');
});

test('add people: function members first with their group, selection count, one POST for all', async () => {
  const { column, button, topDialog, writes } = await managePage({
    'POST /api/manage/groups/3/members': { body: { groupId: 3, moved: [] } }
  });
  button(column('Empty team'), 'Add people').click();
  await settle();
  const dialog = topDialog();
  const rows = [...dialog.querySelectorAll('.admin-add-row')];
  assertEqual(rows.length, 2, 'function members');
  assertTrue(rows[0].textContent.includes('IT Operations') && rows[1].textContent.includes('No group'), 'current group shown');
  const add = button(dialog, 'Add');
  assertTrue(add.disabled, 'nothing chosen yet');
  rows.forEach((r) => { const box = r.querySelector('input'); box.checked = true; box.dispatchEvent(new (require('./support/mini-dom').Event)('change')); });
  assertTrue(!add.disabled);
  assertTrue(dialog.textContent.includes('2 selected'));
  add.click();
  await settle();
  assertEqual(writes()[0].body, { employeeIds: [11, 13] });
});

test('group names and people from the server stay text in the manage view', async () => {
  const evil = groupsAnswer();
  evil.groups[2].name = PAYLOAD;
  evil.ungrouped[0].name = PAYLOAD;
  const { manage } = await managePage({ 'GET /api/manage/functions/ITS/groups': { body: evil } });
  assertEqual(manage().querySelectorAll('img').length, 0, 'no element from a payload');
  assertTrue(manage().textContent.includes(PAYLOAD), 'shown as text');
});

test('hide from site: the dialog of the prompt, an optional reason, POST, warnings in the toast', async () => {
  const page = adminPage({
    'POST /api/manage/employees/5/hide': { body: { employeeId: 5, hidden: true, warnings: ['No longer a head of ITS.'] } }
  });
  page.ctx.sandbox.employee = { id: 5, cs: 'ITS', name: 'Kilo, Khaki' };
  page.ctx.run('hideFromSite(employee)');
  await settle();
  const dialog = page.topDialog();
  assertEqual(dialog.querySelector('.ui-dialog-title').textContent, 'Hide Kilo, Khaki from the site?');
  assertTrue(dialog.textContent.includes('Kilo, Khaki will no longer appear in the tree, groups or search for anyone. People reporting to them are shown under their next manager. You can restore them in Admin > Hidden people.'), 'text');
  assertTrue(page.button(dialog, 'Hide') && page.button(dialog, 'Cancel'), 'buttons');
  page.type(dialog.querySelector('input'), '<b>');
  page.button(dialog, 'Hide').click();
  await settle();
  assertEqual(page.writes().length, 0, 'invalid reason: no request');
  page.type(dialog.querySelector('input'), '  On  leave ');
  page.button(dialog, 'Hide').click();
  await settle();
  assertEqual(page.writes()[0].body, { reason: 'On leave' });
  assertTrue(page.document.querySelector('.ui-toast-message').textContent.includes('No longer a head of ITS.'), 'warning shown');
});

// --- the public Tree view shows the group as a chip (stored XSS too) ------------------------------------------

test('tree card: the group chip, never for HEAD, and as text', () => {
  const fn = context(['common.js', 'function.js']);
  const card = (emp) => String(fn.run(`renderEmpCard(${JSON.stringify({ _id: 0, ...emp })}, { groupChip: true })`));
  assertTrue(card({ name: 'A', group: 'IT PMO' }).includes('emp-card-group" title="IT PMO">IT PMO<'), 'chip');
  assertTrue(!card({ name: 'A', group: 'HEAD' }).includes('emp-card-group'), 'no chip for heads');
  const evil = card({ name: 'A', group: PAYLOAD });
  assertTrue(!evil.includes('<img') && evil.includes('&lt;img'), 'escaped');
});

done('admin groups check');
