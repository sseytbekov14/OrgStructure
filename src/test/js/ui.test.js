// U2: the shared UI components of static/js/ui.js on a minimal DOM: dialog (role, aria-modal, focus trap, ESC,
// backdrop, focus return, beforeClose), confirmDialog, toast (types, undo, dismiss) and collapsible (aria-expanded,
// sessionStorage).
// Run: node src/test/js/ui.test.js
const { context, runner, assertEqual, assertTrue } = require('./support/sandbox');
const { domGlobals, key, Event } = require('./support/mini-dom');

const { test, done } = runner();

function page() {
  const globals = domGlobals();
  const ctx = context(['ui.js'], globals);
  const { document } = globals;
  const opener = document.createElement('button');
  opener.textContent = 'Open';
  document.body.append(opener);
  opener.focus();
  return { ctx, document, opener, globals };
}

test('dialog: accessible markup, labelled by its title, appended to the body', () => {
  const { ctx, document } = page();
  ctx.sandbox.handle = ctx.run(`openDialog({ title: 'Edit <b>x</b>', body: 'Text', actions: [{ label: 'Save', variant: 'primary' }] })`);
  const dialog = document.querySelector('[role=dialog]');
  assertTrue(dialog, 'dialog present');
  assertEqual(dialog.getAttribute('aria-modal'), 'true');
  const title = document.getElementById(dialog.getAttribute('aria-labelledby'));
  assertEqual(title.textContent, 'Edit <b>x</b>', 'title is text, never HTML');
  assertEqual(title.childNodes.length, 1, 'one text node');
});

test('dialog: focus moves inside, Tab and Shift+Tab are trapped', () => {
  const { ctx, document } = page();
  ctx.run(`openDialog({ title: 'T', actions: [{ label: 'A' }, { label: 'B' }] })`);
  const dialog = document.querySelector('[role=dialog]');
  const buttons = dialog.querySelectorAll('button');
  const close = buttons[0];
  const last = buttons[buttons.length - 1];
  assertTrue(dialog.contains(document.activeElement), 'focus inside the dialog');
  last.focus();
  const forward = key(last, 'Tab');
  assertTrue(forward.defaultPrevented, 'Tab on the last element is handled');
  assertEqual(document.activeElement, close, 'wraps to the first element');
  const back = key(close, 'Tab', { shiftKey: true });
  assertTrue(back.defaultPrevented, 'Shift+Tab on the first element is handled');
  assertEqual(document.activeElement, last, 'wraps to the last element');
  close.focus();
  const middle = key(close, 'Tab');
  assertTrue(!middle.defaultPrevented, 'Tab in the middle is left to the browser');
});

test('dialog: ESC closes it and returns the focus to the opener', async () => {
  const { ctx, document, opener } = page();
  const handle = ctx.run(`openDialog({ title: 'T', body: 'x' })`);
  const dialog = document.querySelector('[role=dialog]');
  key(dialog, 'Escape');
  assertEqual(await handle.closed, null, 'closed with null');
  assertTrue(!document.querySelector('[role=dialog]'), 'removed from the page');
  assertEqual(document.activeElement, opener, 'focus back on the opener');
});

test('dialog: backdrop click closes, unless closeOnBackdrop is false', async () => {
  const { ctx, document } = page();
  ctx.run(`openDialog({ title: 'Sticky', closeOnBackdrop: false })`);
  const backdrop = document.querySelector('.ui-dialog-backdrop');
  backdrop.dispatchEvent(new Event('click'));
  assertTrue(document.querySelector('[role=dialog]'), 'still open');
  const handle = ctx.run(`uiDialogStack[0]`);
  handle.close();
  const second = ctx.run(`openDialog({ title: 'Closable' })`);
  document.querySelector('.ui-dialog-backdrop').dispatchEvent(new Event('click'));
  assertEqual(await second.closed, null, 'closed by the backdrop');
});

test('dialog: the close button and the action values', async () => {
  const { ctx, document } = page();
  const handle = ctx.run(`openDialog({ title: 'T', actions: [{ label: 'Yes', value: 'yes' }] })`);
  [...document.querySelectorAll('button')].find((b) => b.textContent === 'Yes').click();
  assertEqual(await handle.closed, 'yes');
  const other = ctx.run(`openDialog({ title: 'T' })`);
  document.querySelector('.ui-dialog-close').click();
  assertEqual(await other.closed, null);
});

test('dialog: beforeClose can keep it open (dirty form)', async () => {
  const { ctx, document } = page();
  ctx.run(`var allow = false; var asked = 0; var h = openDialog({ title: 'Form', beforeClose: () => { asked++; return allow; } })`);
  key(document.querySelector('[role=dialog]'), 'Escape');
  await new Promise((r) => setImmediate(r));
  assertTrue(document.querySelector('[role=dialog]'), 'kept open');
  assertEqual(ctx.run('asked'), 1);
  ctx.run('allow = true');
  key(document.querySelector('[role=dialog]'), 'Escape');
  await ctx.run('h.closed');
  assertTrue(!document.querySelector('[role=dialog]'), 'closed once allowed');
});

test('dialog: only the top dialog reacts to ESC', async () => {
  const { ctx, document } = page();
  ctx.run(`var first = openDialog({ title: 'First', body: 'a' }); var second = openDialog({ title: 'Second', body: 'b' })`);
  const dialogs = document.querySelectorAll('[role=dialog]');
  key(dialogs[0], 'Escape');
  assertEqual(document.querySelectorAll('[role=dialog]').length, 2, 'the lower dialog ignores ESC');
  key(dialogs[1], 'Escape');
  await ctx.run('second.closed');
  assertEqual(document.querySelectorAll('[role=dialog]').length, 1, 'top one closed');
});

test('confirmDialog: true on confirm, false on cancel and ESC; danger focuses Cancel', async () => {
  const { ctx, document } = page();
  let answer = ctx.run(`confirmDialog({ title: 'Remove?', message: 'Sure?', confirmLabel: 'Remove', danger: true })`);
  assertEqual(document.activeElement.textContent, 'Cancel', 'destructive: focus on Cancel');
  [...document.querySelectorAll('button')].find((b) => b.textContent === 'Remove').click();
  assertEqual(await answer, true);
  answer = ctx.run(`confirmDialog({ title: 'Remove?', confirmLabel: 'Remove' })`);
  [...document.querySelectorAll('button')].find((b) => b.textContent === 'Cancel').click();
  assertEqual(await answer, false);
  answer = ctx.run(`confirmDialog({ title: 'Remove?' })`);
  key(document.querySelector('[role=dialog]'), 'Escape');
  assertEqual(await answer, false);
});

test('toast: text only, polite region, error is an alert, dismiss and timeout', () => {
  const { ctx, document, globals } = page();
  ctx.run(`toast('<img src=x onerror=alert(1)>')`);
  const host = document.querySelector('.ui-toasts');
  assertEqual(host.getAttribute('aria-live'), 'polite');
  const first = host.querySelector('.ui-toast-message');
  assertEqual(first.textContent, '<img src=x onerror=alert(1)>', 'shown as text');
  ctx.run(`toast('Failed', 'error')`);
  assertEqual(host.querySelectorAll('.ui-toast')[1].getAttribute('role'), 'alert');
  host.querySelector('.ui-toast-close').click();
  assertEqual(host.querySelectorAll('.ui-toast').length, 1, 'dismissed');
  globals.flushTimers();
  assertEqual(host.querySelectorAll('.ui-toast').length, 0, 'timed out');
});

test('toast: Undo runs the callback once and closes the toast', () => {
  const { ctx, document } = page();
  ctx.run(`var undone = 0; toast('Moved', 'success', { undo: () => undone++ })`);
  const action = document.querySelector('.ui-toast-action');
  assertEqual(action.textContent, 'Undo');
  action.click();
  assertEqual(ctx.run('undone'), 1);
  assertEqual(document.querySelectorAll('.ui-toast').length, 0);
});

test('collapsible: aria-expanded, aria-controls, hidden body, state in sessionStorage', () => {
  const { ctx, document, globals } = page();
  const panel = ctx.run(`createCollapsible({ title: 'Function heads', content: document.createTextNode('x'), storageKey: 'k' })`);
  document.body.append(panel.element);
  const header = panel.header;
  assertEqual(header.getAttribute('aria-expanded'), 'false', 'collapsed by default');
  assertTrue(panel.body.hidden, 'body hidden');
  assertEqual(header.getAttribute('aria-controls'), panel.body.id);
  header.click();
  assertEqual(header.getAttribute('aria-expanded'), 'true');
  assertTrue(!panel.body.hidden, 'body shown');
  assertEqual(globals.sessionStorage.getItem('k'), 'true', 'remembered');
  const again = ctx.run(`createCollapsible({ title: 'Function heads', storageKey: 'k' })`);
  assertEqual(again.header.getAttribute('aria-expanded'), 'true', 'restored from sessionStorage');
});

// Regression (found in the U3 browser run): a dialog opened from the profile card (a native modal <dialog>) was
// below it and could not be clicked. Dialogs are native modal dialogs now (top layer, the page behind is inert).
test('dialog: shown as a native modal dialog and closed again', async () => {
  const { ctx, document } = page();
  const handle = ctx.run(`openDialog({ title: 'T' })`);
  const backdrop = document.querySelector('.ui-dialog-backdrop');
  assertEqual(backdrop.tagName, 'DIALOG');
  assertTrue(backdrop.open, 'opened with showModal');
  const cancel = new Event('cancel');
  backdrop.dispatchEvent(cancel);
  assertTrue(cancel.defaultPrevented, 'the browser ESC close is replaced by requestClose');
  handle.close();
  assertTrue(!backdrop.open && !backdrop.isConnected, 'closed and removed');
});

test('toasts are a manual popover (above modal dialogs)', () => {
  const { ctx, document } = page();
  ctx.run(`toast('Saved')`);
  assertEqual(document.querySelector('.ui-toasts').getAttribute('popover'), 'manual');
});

test('uiEl never parses HTML', () => {
  const { ctx } = page();
  const node = ctx.run(`uiEl('p', { text: '<b>bold</b>' })`);
  assertEqual(node.childNodes.length, 1);
  assertEqual(node.textContent, '<b>bold</b>');
});

done('ui components check');
