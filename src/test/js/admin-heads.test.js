// Regression (found in the U2 screenshots): the "Function heads" block showed the text "nullnull" because missing
// optional parts were passed to replaceChildren as null.
// Run: node src/test/js/admin-heads.test.js
const { context, runner, assertTrue } = require('./support/sandbox');
const { domGlobals } = require('./support/mini-dom');

const { test, done } = runner();

function page() {
  const globals = domGlobals({ fetch: () => new Promise(() => {}), Headers: class { set() {} } });
  const chart = globals.document.createElement('div');
  chart.id = 'orgChart';
  globals.document.body.append(chart);
  const ctx = context(['ui.js', 'common.js', 'admin.js'], globals);
  ctx.run('function debounce(fn) { return fn; }');
  return { ctx, document: globals.document };
}

test('heads block with heads and no hidden heads shows no "null"', () => {
  const { ctx, document } = page();
  ctx.run(`AdminHeads.cs = 'ITS'; AdminHeads.expected = [1]; AdminHeads.hidden = 0;
    AdminHeads.heads = [{ id: 1, name: 'Kilo, Khaki', jobTitle: 'CIO' }]; renderAdminHeads();`);
  const section = document.getElementById('adminHeads');
  assertTrue(section, 'rendered');
  assertTrue(!section.textContent.includes('null'), `no "null" text: ${section.textContent}`);
  assertTrue(section.textContent.includes('Kilo, Khaki'), 'head listed');
});

test('heads block without heads shows the warning and no "null"', () => {
  const { ctx, document } = page();
  ctx.run(`AdminHeads.cs = 'ITS'; AdminHeads.expected = []; AdminHeads.hidden = 0; AdminHeads.heads = []; renderAdminHeads();`);
  const text = document.getElementById('adminHeads').textContent;
  assertTrue(!text.includes('null'), 'no "null" text');
  assertTrue(text.includes('no active head'), 'warning shown');
});

done('admin heads check');
