// The order of the people inside a group: inGroupOrder (only people of one group change places, positions first, then
// the given order; other groups and people without a group stay put), the Groups columns and the tree stacks follow
// it, orderSiblings says who can be swapped, movedOrder builds the new list; the arrows of Manage groups and the bar on
// the cards save the whole group and stop at the ends. No inline handlers, no innerHTML.
// Run: node src/test/js/group-order.test.js
const { context, readStatic, runner, assertEqual, assertTrue } = require('./support/sandbox');
const { adminPage, settle } = require('./support/admin-page');
const { domGlobals, Event, key } = require('./support/mini-dom');

const { test, done } = runner();

function chartPage() {
  const globals = domGlobals({ fetch: () => new Promise(() => {}), Headers: class { set() {} } });
  const { document } = globals;
  for (const id of ['faqBanner', 'funcCount', 'funcTitle', 'funcSearchCount', 'orgChart', 'treeEdges', 'treeLabels',
    'treeOverlay', 'treeStage', 'treeViewport', 'treeZoomValue', 'funcSearchNext', 'funcSearchPrev']) {
    const el = document.createElement(id.startsWith('funcSearch') && id !== 'funcSearchCount' ? 'button' : 'div');
    el.id = id;
    document.body.append(el);
  }
  const search = document.createElement('input');
  search.id = 'funcSearch';
  document.body.append(search);
  return { globals, ctx: context(['ui.js', 'common.js', 'function.js'], globals) };
}

const person = (id, name, groupId, groupPosition, extra = {}) => ({ id, name, email: `${id}@kz.example`, jobTitle: 'Specialist',
  gradeOrder: 5, cs: 'ITS', group: groupId === 1 ? 'Ops' : groupId === 2 ? 'PMO' : groupId === 'other' ? null : 'HEAD',
  groupId: groupId === 'other' ? undefined : groupId, groupPosition, ...extra });

const order = (ctx, items) => JSON.parse(ctx.run(
  `JSON.stringify(inGroupOrder(${JSON.stringify(items)}).map((p) => p.name))`));

test('inGroupOrder: positions inside a group, who has none follows; other groups keep their places', () => {
  const { ctx } = chartPage();
  assertEqual(order(ctx, [person(1, 'Boss', 1, 30), person(2, 'Junior', 1, 10), person(3, 'Mid', 1, 20)]),
    ['Junior', 'Mid', 'Boss']);
  assertEqual(order(ctx, [person(1, 'A', 1), person(2, 'B', 1, 10), person(3, 'C', 1)]), ['B', 'A', 'C']);
  // Slots of group 1 are 0 and 2: the PMO person between them does not move
  assertEqual(order(ctx, [person(1, 'A', 1, 20), person(2, 'P', 2, 10), person(3, 'B', 1, 10)]), ['B', 'P', 'A']);
  // Excel rows (no ids) and heads: nothing changes
  assertEqual(order(ctx, [{ name: 'X' }, { name: 'Y', groupPosition: 1 }]), ['X', 'Y']);
  assertEqual(order(ctx, [person(1, 'H1', null, 20), person(2, 'H2', null, 10)]), ['H1', 'H2']);
  // The people without a group (the Other column) are a group of their own
  assertEqual(order(ctx, [person(1, 'A', 'other', 20), person(2, 'B', 'other', 10)]), ['B', 'A']);
});

test('the Groups view shows the column in that order, higher grades below when an admin put them there', () => {
  const { ctx } = chartPage();
  const people = [person(1, 'Manager', 1, 20, { gradeOrder: 1 }), person(2, 'Analyst', 1, 10, { gradeOrder: 9 })];
  const html = String(ctx.run(`renderGroupsView(${JSON.stringify(people)})`));
  assertTrue(html.indexOf('Analyst') < html.indexOf('Manager'), 'the position wins over the grade');
  const byGrade = people.map((p) => ({ ...p, groupPosition: undefined }));
  const auto = String(ctx.run(`renderGroupsView(${JSON.stringify(byGrade)})`));
  assertTrue(auto.indexOf('Manager') < auto.indexOf('Analyst'), 'without positions: by grade, as before');
});

test('the tree keeps the order among colleagues under the same manager', () => {
  const { ctx } = chartPage();
  const people = [person(100, 'Head', null, undefined), person(1, 'Manager', 1, 20, { gradeOrder: 1, parentId: 100 }),
    person(2, 'Analyst', 1, 10, { gradeOrder: 9, parentId: 100 })].map((p, i) => ({ ...p, _id: i }));
  ctx.run(`employeeDirectory = ${JSON.stringify(people)}; currentView = 'tree'; buildTree(employeeDirectory);`);
  assertEqual(JSON.parse(ctx.run('JSON.stringify(tree.root.children.map((c) => c.emp.name))')), ['Analyst', 'Manager']);
  const place = JSON.parse(ctx.run('JSON.stringify(orderSiblings(employeeDirectory[1]))'));
  assertEqual(place.list.map((p) => p.name), ['Analyst', 'Manager']);
  assertEqual(place.horizontal, false, 'a stack of leaves is vertical');
  assertEqual(ctx.run('orderSiblings(employeeDirectory[0])'), null, 'the head has no place in a group');
});

test('orderSiblings in the Groups view: the whole group, the Other column, nothing for heads', () => {
  const { ctx } = chartPage();
  const people = [person(1, 'A', 1, 20), person(2, 'B', 1, 10), person(3, 'C', 'other'), person(4, 'D', 'other', 10),
    person(5, 'Head', null)].map((p, i) => ({ ...p, _id: i }));
  ctx.run(`employeeDirectory = ${JSON.stringify(people)}; currentView = 'groups';`);
  assertEqual(JSON.parse(ctx.run('JSON.stringify(orderSiblings(employeeDirectory[0]).list.map((p) => p.name))')), ['B', 'A']);
  assertEqual(JSON.parse(ctx.run('JSON.stringify(orderSiblings(employeeDirectory[2]).list.map((p) => p.name))')), ['D', 'C']);
  assertEqual(ctx.run('orderSiblings(employeeDirectory[4])'), null);
});

const GROUP = { id: 7, cs: 'ITS', name: 'Ops', kind: 'TEAM', source: 'AUTO', sortOrder: 10, active: true, version: 0, memberCount: 3,
  members: [{ employeeId: 11, name: 'First', source: 'AD' }, { employeeId: 12, name: 'Second', source: 'AD' },
    { employeeId: 13, name: 'Third', source: 'MANUAL' }] };

async function managePage(routes = {}) {
  const page = adminPage({
    'GET /api/manage/functions/ITS/groups': { body: { cs: 'ITS', groups: [GROUP], heads: [],
      ungrouped: [{ employeeId: 21, name: 'Loose' }, { employeeId: 22, name: 'Free' }] } },
    'PUT /api/manage/groups/7/order': { status: 204, body: {} },
    'DELETE /api/manage/groups/7/order': { status: 204, body: {} },
    'PUT /api/manage/functions/ITS/order': { status: 204, body: {} },
    ...routes
  });
  page.ctx.run('function faqIcon() { return document.createElement("span"); }');
  await page.ctx.run(`window.onFunctionRendered('ITS', [])`);
  await settle();
  page.document.getElementById('adminGroupsToggle').click();
  await settle();
  return page;
}

const arrow = (page, label) => [...page.document.querySelectorAll('button')].find((b) => b.getAttribute('aria-label') === label);

test('movedOrder: right before or right after the other person', () => {
  const { ctx } = adminPage();
  const moved = (args) => JSON.parse(ctx.run(`JSON.stringify(movedOrder(${args}))`));
  assertEqual(moved('[1, 2, 3], 3, 2, true'), [1, 3, 2]);
  assertEqual(moved('[1, 2, 3], 1, 2, false'), [2, 1, 3]);
  assertEqual(moved('[1, 2, 3, 4], 4, 2, true'), [1, 4, 2, 3]);
});

test('Manage groups: arrows on every member, off at the ends, the whole group is saved', async () => {
  const page = await managePage();
  assertTrue(arrow(page, 'Move First up').disabled, 'the first cannot go up');
  assertTrue(!arrow(page, 'Move First down').disabled, 'the first can go down');
  assertTrue(arrow(page, 'Move Third down').disabled, 'the last cannot go down');
  arrow(page, 'Move Third up').click();
  await settle();
  const write = page.writes()[0];
  assertEqual(`${write.method} ${write.path}`, 'PUT /api/manage/groups/7/order');
  assertEqual(write.body.employeeIds, [11, 13, 12]);
});

test('Manage groups: a refused move is a message, the menu resets to the order by grade', async () => {
  const page = await managePage({ 'PUT /api/manage/groups/7/order': { status: 409, body: { code: 'CHANGED', message: 'Changed by someone else' } } });
  arrow(page, 'Move Second up').click();
  await settle();
  assertTrue(page.document.querySelector('.ui-toast-message').textContent.includes('Changed by someone else'), 'toast');
  const reset = [...page.document.querySelectorAll('.admin-group-menu-item')].find((b) => b.textContent.startsWith('Reset order'));
  assertTrue(!reset.disabled, 'three people: can be reset');
  page.document.querySelector('.admin-group-menu-btn').click();
  reset.click();
  await settle();
  assertEqual(page.writes().filter((w) => w.method === 'DELETE').map((w) => w.path), ['/api/manage/groups/7/order']);
});

test('Manage groups: the people without a group have arrows too, saved for the function', async () => {
  const page = await managePage();
  arrow(page, 'Move Free up').click();
  await settle();
  const write = page.writes()[0];
  assertEqual(`${write.method} ${write.path}`, 'PUT /api/manage/functions/ITS/order');
  assertEqual(write.body.employeeIds, [22, 21]);
});

test('the bar and the arrows use listeners and DOM APIs only', () => {
  const source = readStatic('js/admin-groups.js');
  const part = source.slice(source.indexOf('/* ---------- The order inside a group'), source.indexOf('/* ---------- Moves ---------- */'));
  assertTrue(part.includes('function showOrderBar'), 'found');
  assertTrue(!/innerHTML|\son[a-z]+=/.test(part), 'no innerHTML, no inline handler');
});

done('group order check');
