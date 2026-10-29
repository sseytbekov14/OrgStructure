// U3: the edit form (dialog, validation, Save state, no-op, dirty close, double submit, 400 / 409 handling, Enter),
// the reset / remove dialogs by context, and regression tests of the bugs found in the old edit flow.
// Run: node src/test/js/admin-edit.test.js
const { runner, assertEqual, assertTrue } = require('./support/sandbox');
const { adminPage, settle } = require('./support/admin-page');
const { key, Event } = require('./support/mini-dom');

const { test, done } = runner();

const MIKE = { id: 5, cs: 'ITS', name: 'Mint, Mike', email: 'mike.mint@kz.example' };
function member(extra = {}) {
  return {
    employeeId: 5, name: 'Mint, Mike', email: 'mike.mint@kz.example', jobTitle: 'System Administrator', countryCode: 'KZ',
    cs: 'ITS', group: 'Service Desk', isPrimaryFunction: true, adDefaultGroup: 'IT Operations',
    override: { employeeId: 5, cs: 'ITS', groupName: 'Service Desk', headRank: null, areaOfDuties: 'Workplace IT',
      jobDescription: 'User support', version: 3 },
    ...extra
  };
}

/** GET /api/manage/functions/{cs}/groups: the groups of the function as Manage groups lists them */
function groupsOf(cs, names, inactive = []) {
  return { body: { cs, heads: [], ungrouped: [], groups: [
    ...names.map((name, i) => ({ id: i + 1, cs, name, active: true, members: [], memberCount: 0 })),
    ...inactive.map((name, i) => ({ id: 100 + i, cs, name, active: false, members: [], memberCount: 0 }))] } };
}

const ITS_GROUPS = ['Service Desk', 'IT Operations', 'IT PMO', 'Workplace', 'Leads', 'Another team', 'Service Desk 2'];

async function openEditor(routes = {}, employee = MIKE, memberRow = member()) {
  const page = adminPage({ 'GET /api/manage/overrides': { body: [memberRow] },
    'GET /api/manage/functions/ITS/groups': groupsOf('ITS', ITS_GROUPS), ...routes });
  page.ctx.sandbox.employee = employee;
  const editor = await page.ctx.run('openAdminEditor(employee)');
  await settle();
  const dialog = page.topDialog();
  const input = (name) => dialog.querySelector(`[name=${name}]`);
  return { ...page, editor, dialog, input, save: page.button(dialog, 'Save') };
}

test('dialog titled with the person, the function shown, fields with labels, help, AD hint and counter', async () => {
  const { dialog, input } = await openEditor();
  assertEqual(dialog.querySelector('.ui-dialog-title').textContent, 'Edit Mint, Mike');
  assertTrue(dialog.textContent.includes('In ITS'), 'function in context');
  for (const [name, label] of [['groupName', 'Group'], ['areaOfDuties', 'Area of duties'], ['jobDescription', 'Job description']]) {
    const field = input(name);
    assertTrue(field, `${name} present`);
    assertEqual(dialog.querySelector(`label[for=${field.id}]`).textContent, label);
  }
  assertEqual(input('groupName').value, 'Service Desk');
  assertTrue(dialog.textContent.includes('From Active Directory: IT Operations'), 'AD default as hint');
  assertTrue(!dialog.querySelector('#adminEdit-groupName-counter'), 'no character counter for a chosen group');
  assertEqual(dialog.querySelector('#adminEdit-areaOfDuties-counter').textContent, '12 / 120');
  assertEqual(input('areaOfDuties').tagName, 'TEXTAREA');
});

// --- the Group select -----------------------------------------------------------------------------------------

const optionsOf = (select) => [...select.querySelectorAll('option')].map((o) => [o.getAttribute('value'), o.textContent]);

test('group: a select of the function\'s groups, "use the group from AD" first, de-duplicated and sorted', async () => {
  const { input, requests } = await openEditor({
    'GET /api/manage/functions/ITS/groups': groupsOf('ITS', ['Service Desk', 'it pmo', 'IT PMO', 'Analytics'], ['Old team'])
  });
  const select = input('groupName');
  assertEqual(select.tagName, 'SELECT');
  assertTrue(requests.some((r) => r.path === '/api/manage/functions/ITS/groups'), 'the groups of Manage groups');
  assertEqual(optionsOf(select), [['', '— Use group from Active Directory —'], ['Analytics', 'Analytics'],
    ['it pmo', 'it pmo'], ['Service Desk', 'Service Desk']], 'options (inactive groups left out)');
  assertEqual(select.querySelector('option[selected]').getAttribute('value'), 'Service Desk', 'current preselected');
});

test('group: a current value that is no listed group is added, so the select keeps it', async () => {
  const row = member({ override: { ...member().override, groupName: 'Legacy  team' } });
  const { input, save } = await openEditor({}, MIKE, row);
  const select = input('groupName');
  assertTrue(optionsOf(select).some(([value]) => value === 'Legacy  team'), 'current value listed as stored');
  assertEqual(select.value, 'Legacy  team');
  assertTrue(save.disabled, 'nothing changed');
});

test('group: without an override the AD option is chosen; choosing a group and saving sends its name', async () => {
  const row = member({ group: 'IT Operations', override: null });
  const { input, save, writes, type } = await openEditor({ 'PUT /api/manage/overrides/5': { body: {} } }, MIKE, row);
  assertEqual(input('groupName').value, '', 'AD option');
  assertTrue(save.disabled);
  type(input('groupName'), 'IT PMO');
  input('groupName').dispatchEvent(new Event('change'));
  assertTrue(!save.disabled, 'a chosen group can be saved');
  save.click();
  await settle();
  assertEqual(writes()[0].body.groupName, 'IT PMO');
});

test('group: back to "use the group from AD" sends null (the group from Active Directory)', async () => {
  const { input, save, writes, type, dialog } = await openEditor({ 'PUT /api/manage/overrides/5': { body: {} } });
  type(input('groupName'), '');
  save.click();
  await settle();
  assertEqual(writes()[0].body.groupName, null);
  assertTrue(!dialog.isConnected, 'saved');
});

test('group: the "Reset to AD data" button stays next to the select', async () => {
  const { dialog, button } = await openEditor();
  assertTrue(button(dialog, 'Reset to AD data'), 'reset offered');
});

test('group: when the groups cannot be loaded only the current group can be kept', async () => {
  const { input, dialog } = await openEditor({ 'GET /api/manage/functions/ITS/groups': { status: 500, body: {} } });
  assertEqual(optionsOf(input('groupName')).map(([value]) => value), ['', 'Service Desk']);
  assertTrue(dialog.textContent.includes('could not be loaded'), 'explained');
});

test('Save disabled until changed and valid; inline error with the shared message', async () => {
  const { save, input, type, dialog } = await openEditor();
  assertTrue(save.disabled, 'unchanged: disabled');
  type(input('areaOfDuties'), 'Workplace IT and laptops');
  assertTrue(!save.disabled, 'changed and valid: enabled');
  type(input('areaOfDuties'), '<b>x</b>');
  assertTrue(save.disabled, 'invalid: disabled');
  assertEqual(dialog.querySelector('#adminEdit-areaOfDuties-error').textContent, 'Area of duties must not contain < > or `.');
  assertEqual(input('areaOfDuties').getAttribute('aria-invalid'), 'true');
  type(input('areaOfDuties'), '  Workplace   IT ');
  assertTrue(save.disabled, 'same value after normalization: unchanged');
});

test('regression: no request when nothing changed (the old form always sent a PUT)', async () => {
  const { editor, writes } = await openEditor();
  await editor.submit();
  await settle();
  assertEqual(writes().length, 0);
});

test('save: one PUT with the normalized values and the version, toast, in-place refresh, no page reload', async () => {
  const { editor, input, type, save, requests, writes, ctx, document } = await openEditor({
    'PUT /api/manage/overrides/5': { body: { version: 4 } }
  });
  type(input('jobDescription'), '  Laptops   and accounts ');
  save.click();
  save.click();
  await settle();
  assertEqual(writes().length, 1, 'double submit protection');
  const put = writes()[0];
  assertTrue(put.url.includes('cs=ITS'), 'function in the request');
  assertEqual(put.body, { groupName: 'Service Desk', areaOfDuties: 'Workplace IT', jobDescription: 'Laptops and accounts', version: 3 });
  assertEqual(document.querySelectorAll('[role=dialog]').length, 0, 'closed');
  assertTrue(document.querySelector('.ui-toast--success'), 'success toast');
  assertEqual(ctx.run('refreshes'), 1, 'chart refreshed in place');
  assertTrue(requests.every((r) => r.method !== 'RELOAD'), 'no reload');
  assertTrue(editor.state.saving === 'done', 'state done');
});

test('busy state while saving', async () => {
  const { input, type, save, writes } = await openEditor({
    'PUT /api/manage/overrides/5': () => ({ status: 200, body: {} })
  });
  type(input('groupName'), 'Service Desk 2');
  save.click();
  assertEqual(save.getAttribute('aria-busy'), 'true', 'busy during the request');
  assertTrue(save.disabled, 'disabled during the request');
  await settle();
  assertEqual(writes().length, 1);
  assertTrue(!save.hasAttribute('aria-busy'), 'not busy afterwards');
});

test('400: the server field errors are shown next to their fields', async () => {
  const { input, type, save, dialog } = await openEditor({
    'PUT /api/manage/overrides/5': { status: 400, body: { code: 'VALIDATION', errors: { groupName: { code: 'RESERVED', message: 'HEAD is reserved for the heads of the function: use the Function heads panel.' } } } }
  });
  type(input('groupName'), 'Leads');
  save.click();
  await settle();
  assertEqual(dialog.querySelector('#adminEdit-groupName-error').textContent, 'HEAD is reserved for the heads of the function: use the Function heads panel.');
  assertTrue(dialog.isConnected, 'still open');
});

test('409: "Changed by someone else", Reload values keeps the typed text and takes the new version', async () => {
  const page = await openEditor({
    'PUT /api/manage/overrides/5': [{ status: 409, body: { code: 'CHANGED', message: 'someone else changed this entry' } }, { status: 200, body: {} }]
  });
  const { input, type, save, dialog, button, answers, writes } = page;
  type(input('areaOfDuties'), 'My new text');
  save.click();
  await settle();
  const alert = dialog.querySelector('.admin-form-alert');
  assertTrue(!alert.hidden && alert.textContent.includes('Changed by someone else'), 'conflict message');
  answers['GET /api/manage/overrides'] = { body: [member({ override: { ...member().override, areaOfDuties: 'Saved by Bob', version: 7 } })] };
  button(dialog, 'Reload values').click();
  await settle();
  assertEqual(input('areaOfDuties').value, 'My new text', 'typed text kept');
  assertTrue(dialog.textContent.includes('Saved value: Saved by Bob'), 'saved value shown');
  assertTrue(!save.disabled, 'can save again');
  save.click();
  await settle();
  assertEqual(writes()[1].body.version, 7, 'saved with the new version');
});

test('dirty close asks; Cancel keeps the form, Discard closes; a clean form closes at once', async () => {
  const { input, type, dialog, topDialog, button, dialogs } = await openEditor();
  type(input('groupName'), 'Another team');
  key(dialog, 'Escape');
  await settle();
  const ask = topDialog();
  assertEqual(ask.querySelector('.ui-dialog-title').textContent, 'Discard your changes?');
  button(ask, 'Cancel').click();
  await settle();
  assertEqual(dialogs().length, 1, 'editor kept');
  assertEqual(input('groupName').value, 'Another team', 'text kept');
  button(dialog, 'Cancel').click();
  await settle();
  button(topDialog(), 'Discard').click();
  await settle();
  assertEqual(dialogs().length, 0, 'closed after Discard');

  const clean = await openEditor();
  key(clean.dialog, 'Escape');
  await settle();
  assertEqual(clean.dialogs().length, 0, 'clean form closes without asking');
});

test('Enter submits from a one-line field, never from a textarea', async () => {
  const { input, type, writes } = await openEditor({ 'PUT /api/manage/overrides/5': { body: {} } });
  type(input('areaOfDuties'), 'Line one');
  const inTextarea = key(input('areaOfDuties'), 'Enter');
  await settle();
  assertTrue(!inTextarea.defaultPrevented, 'Enter in a textarea is a line break');
  assertEqual(writes().length, 0, 'no submit from the textarea');
  key(input('groupName'), 'Enter');
  await settle();
  assertEqual(writes().length, 1, 'Enter in the group field submits');
});

test('two-function person: the function in context is edited and shown', async () => {
  const hhoney = { id: 9, cs: 'F&O', name: 'Hotel, Honey', email: 'honey@kz.example' };
  const row = member({ employeeId: 9, name: 'Hotel, Honey', cs: 'F&O', isPrimaryFunction: false, adDefaultGroup: null,
    override: { employeeId: 9, cs: 'F&O', groupName: 'Office support', areaOfDuties: null, jobDescription: null, version: 0 } });
  const { dialog, requests, button } = await openEditor({}, hhoney, row);
  assertTrue(dialog.textContent.includes('In F&O'), 'function shown');
  assertTrue(requests.find((r) => r.path === '/api/manage/overrides').url.includes('cs=F%26O'), 'loaded for F&O');
  assertTrue(dialog.textContent.includes('extra function'), 'AD hint for an extra function');
  assertTrue(button(dialog, 'Remove from function'), 'remove named by context');
  assertTrue(!button(dialog, 'Reset to AD data'), 'no reset in an extra function');
});

test('Reset to AD data: dialog title and text, DELETE with the version after confirming', async () => {
  const { dialog, button, topDialog, writes } = await openEditor({ 'DELETE /api/manage/overrides/5': { status: 204, body: {} } });
  button(dialog, 'Reset to AD data').click();
  await settle();
  const ask = topDialog();
  assertEqual(ask.querySelector('.ui-dialog-title').textContent, 'Reset to Active Directory data?');
  assertEqual(ask.querySelector('.ui-dialog-body').textContent, 'Manual changes for Mint, Mike in ITS (group, area of duties, job description, head status and reporting line) will be removed. Mint, Mike stays on the site with the data from Active Directory.');
  assertTrue(button(ask, 'Reset') && button(ask, 'Cancel'), 'buttons Reset / Cancel');
  button(ask, 'Reset').click();
  await settle();
  assertEqual(writes().length, 1);
  assertTrue(writes()[0].url.includes('version=3'), 'with the version');
});

test('Remove from function: dialog title and text; Cancel sends nothing', async () => {
  const row = member({ isPrimaryFunction: false, adDefaultGroup: null });
  const { dialog, button, topDialog, writes } = await openEditor({}, { ...MIKE, cs: 'QRM' }, { ...row, cs: 'QRM' });
  button(dialog, 'Remove from function').click();
  await settle();
  const ask = topDialog();
  assertEqual(ask.querySelector('.ui-dialog-title').textContent, 'Remove Mint, Mike from QRM?');
  assertEqual(ask.querySelector('.ui-dialog-body').textContent, 'Mint, Mike will no longer appear in QRM. They stay in their own function.');
  button(ask, 'Cancel').click();
  await settle();
  assertEqual(writes().length, 0);
});

test('last head: a second dialog before confirmLastHead=true', async () => {
  const { dialog, button, topDialog, writes } = await openEditor({
    'DELETE /api/manage/overrides/5': [{ status: 409, body: { code: 'LAST_HEAD', message: 'last head' } }, { status: 204, body: {} }]
  });
  button(dialog, 'Reset to AD data').click();
  await settle();
  button(topDialog(), 'Reset').click();
  await settle();
  assertTrue(topDialog().querySelector('.ui-dialog-title').textContent.includes('last head'), 'asked again');
  button(topDialog(), 'Reset').click();
  await settle();
  assertEqual(writes().length, 2);
  assertTrue(writes()[1].url.includes('confirmLastHead=true'), 'confirmed');
});

test('a stored value from before the rules (too long) does not block editing another field', async () => {
  const legacy = 'x'.repeat(300) + '  with  spaces';
  const row = member({ override: { ...member().override, jobDescription: legacy } });
  const { input, type, save, dialog, writes } = await openEditor({ 'PUT /api/manage/overrides/5': { body: {} } }, MIKE, row);
  assertEqual(dialog.querySelector('#adminEdit-jobDescription-error').textContent, '', 'no error for the untouched value');
  assertTrue(save.disabled, 'nothing changed yet');
  type(input('groupName'), 'Workplace');
  assertTrue(!save.disabled, 'the other field can be saved');
  save.click();
  await settle();
  assertEqual(writes()[0].body.jobDescription, legacy, 'sent back exactly as stored');
  type(input('jobDescription'), legacy + '!');
});

test('XSS payloads stay text in the form, the hints and the dialogs', async () => {
  const payload = '<img src=x onerror=alert(1)>';
  const row = member({ name: payload, adDefaultGroup: payload, override: { ...member().override, groupName: payload } });
  const { dialog, button, topDialog } = await openEditor({}, { ...MIKE, name: payload }, row);
  assertTrue(dialog.querySelectorAll('img').length === 0, 'no element from a payload');
  assertTrue(dialog.textContent.includes(`From Active Directory: ${payload}`), 'hint as text');
  button(dialog, 'Reset to AD data').click();
  await settle();
  assertTrue(topDialog().querySelectorAll('img').length === 0, 'no element in the dialog');
  assertTrue(topDialog().textContent.includes(payload), 'name as text');
});

done('admin edit check');
