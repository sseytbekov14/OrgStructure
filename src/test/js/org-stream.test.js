// Regression (found in the U7 review): showOrgToast declared a local "toast" that shadowed ui.js's toast(), so
// "typeof toast" threw (temporal dead zone) and an SSE refresh never announced itself. Now: refresh, then the toast.
// Run: node src/test/js/org-stream.test.js
const { context, runner, assertEqual, assertTrue } = require('./support/sandbox');
const { domGlobals } = require('./support/mini-dom');

const { test, done } = runner();

test('an org-updated event refreshes the chart and shows the toast of ui.js', async () => {
  const globals = domGlobals({ fetch: () => new Promise(() => {}), Headers: class { set() {} } });
  const ctx = context(['ui.js', 'common.js', 'org-stream.js'], globals);
  ctx.run('var refreshed = 0; window.refreshOrgChart = async () => { refreshed++; };');
  await ctx.run('handleOrgStreamRefresh({ source: "faq" })');
  assertEqual(ctx.run('refreshed'), 1);
  const message = globals.document.querySelector('.ui-toast-message');
  assertTrue(message, 'toast shown');
  assertEqual(message.textContent, 'Data updated');
});

test('without ui.js nothing throws', () => {
  const ctx = context(['org-stream.js']);
  ctx.run('showOrgToast("x")');
});

done('org stream check');
