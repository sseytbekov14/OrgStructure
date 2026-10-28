// U6: the employee card facts (City / Country only when known, counselee count), the profile ("Reports to",
// "Counselees", City and Country rows), the limited card of an external manager, no copy-e-mail button in the search
// results, and the wording "Counselees" instead of "Direct reports" in every page and script. The profile card has a
// copy button next to the e-mail address again (asked for after the release).
// Run: node src/test/js/cards.test.js
const fs = require('fs');
const path = require('path');
const { context, readStatic, runner, assertEqual, assertTrue } = require('./support/sandbox');
const { domGlobals } = require('./support/mini-dom');

const { test, done } = runner();
const PAYLOAD = '<img src=x onerror=alert(1)>';

const BOSS = { id: 1, cs: 'ITS', name: 'Kilo, Khaki', email: 'kk@kz.example', jobTitle: 'CIO', city: 'Almaty', country: 'Kazakhstan' };
const MIKE = { id: 2, cs: 'ITS', name: 'Mint, Mike', email: 'mm@kz.example', jobTitle: 'Admin', parentId: 1, pmId: 1,
  pmExternal: false, pm: 'Kilo, Khaki', pmEmail: 'kk@kz.example' };
const NOMAD = { id: 3, cs: 'ITS', name: 'Nomad, Nina', email: 'nn@kz.example', jobTitle: 'Analyst', pm: 'Partner, Pat',
  pmEmail: 'pat@x.example', pmJobTitle: 'Managing Partner', pmId: 99, pmExternal: true, city: 'Tashkent', country: 'Uzbekistan' };

function page() {
  const ctx = context(['common.js']);
  ctx.run(`setDirectory(${JSON.stringify([BOSS, MIKE, NOMAD])})`);
  return ctx;
}

test('card facts: location and counselees only when known', () => {
  const ctx = page();
  const boss = String(ctx.run(`renderCardFacts(${JSON.stringify(BOSS)})`));
  assertTrue(boss.includes('Almaty, Kazakhstan') && boss.includes('1 counselee<'), `boss facts: ${boss}`);
  assertEqual(String(ctx.run(`renderCardFacts(${JSON.stringify(MIKE)})`)), '', 'nothing to show');
  const onlyCountry = String(ctx.run(`renderCardFacts({ name: 'X', country: 'Georgia' })`));
  assertTrue(onlyCountry.includes('>Georgia<') && !onlyCountry.includes(', '), 'country alone');
});

test('profile: City and Country rows only when set, "Reports to" and "Counselees"', () => {
  const ctx = page();
  const boss = String(ctx.run(`renderProfile(${JSON.stringify(BOSS)})`));
  assertTrue(boss.includes('<dt>City</dt><dd>Almaty</dd>') && boss.includes('<dt>Country</dt><dd>Kazakhstan</dd>'), 'rows');
  assertTrue(boss.includes('Counselees <span class="profile-count">1</span>'), 'counselee heading');
  const mike = String(ctx.run(`renderProfile(${JSON.stringify(MIKE)})`));
  assertTrue(!mike.includes('profile-facts'), 'no City/Country rows without values');
  assertTrue(mike.includes('>Reports to<') && mike.includes('No counselees'), 'wording');
  assertTrue(!/direct reports/i.test(mike + boss), 'no "Direct reports"');
});

test('profile: the external manager row opens the limited card', () => {
  const ctx = page();
  const html = String(ctx.run(`renderProfile(${JSON.stringify(NOMAD)})`));
  assertTrue(html.includes('data-external-id="99"'), 'clickable external manager');
  assertTrue(html.includes('Outside Central Services'), 'badge');
  const internal = String(ctx.run(`renderPersonRow(findManager(${JSON.stringify(MIKE)}))`));
  assertTrue(internal.includes('data-person-index') && !internal.includes('data-external-id'), 'internal stays a profile link');
});

test('profile: the address is a mailto link with a copy button next to it', () => {
  const ctx = page();
  const html = String(ctx.run(`renderProfile(${JSON.stringify(BOSS)})`));
  assertTrue(html.includes('href="mailto:kk@kz.example"'), 'mailto kept');
  assertTrue(html.includes('data-copy-email="kk@kz.example"') && html.includes('aria-label="Copy email kk@kz.example"'), 'copy button');
  const none = String(ctx.run(`renderProfile(${JSON.stringify({ ...BOSS, email: '' })})`));
  assertTrue(!none.includes('data-copy-email'), 'no copy button without an address');
});

test('the copy button copies the address and confirms it with a toast', async () => {
  const copied = [];
  const globals = domGlobals({ fetch: () => new Promise(() => {}), Headers: class { set() {} },
    navigator: { clipboard: { writeText: (text) => { copied.push(text); return Promise.resolve(); } } },
    isSecureContext: true });
  const ctx = context(['ui.js', 'common.js'], globals);
  assertEqual(await ctx.run('copyEmail("kk@kz.example")'), true);
  assertEqual(copied, ['kk@kz.example']);
  assertEqual(globals.document.querySelector('.ui-toast-message').textContent, 'Email copied');
});

test('without the Clipboard API (http) the address is copied through a hidden text area', async () => {
  const globals = domGlobals({ fetch: () => new Promise(() => {}), Headers: class { set() {} },
    navigator: {}, isSecureContext: false });
  let commands = 0;
  globals.document.execCommand = (name) => { commands += name === 'copy' ? 1 : 0; return true; };
  const ctx = context(['ui.js', 'common.js'], globals);
  assertEqual(await ctx.run('copyEmail("kk@kz.example")'), true);
  assertEqual(commands, 1, 'execCommand("copy")');
  assertEqual(globals.document.querySelectorAll('textarea').length, 0, 'text area removed again');
});

test('search results: no copy button, the card facts are shown', () => {
  const main = context(['common.js', 'main.js']);
  main.run(`setDirectory(${JSON.stringify([BOSS, MIKE, NOMAD])}); searchResults = ${JSON.stringify([BOSS, NOMAD])};
    searchQuery = 'a'; functionFilter = 'All'; renderSearchResults();`);
  const html = String(main.elements.searchResults.innerHTML);
  assertTrue(!html.includes('data-copy') && !/copy/i.test(html), 'no copy button in search results');
  assertTrue(html.includes('Tashkent, Uzbekistan'), 'location');
  assertTrue(html.includes('Reports to <b>Partner, Pat</b>'), 'reports to');
});

test('no copy button in the search results or the FAQ, and no "Direct reports" in the pages and scripts', () => {
  const dir = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
  const files = [...fs.readdirSync(path.join(dir, 'js')).map((f) => `js/${f}`), ...fs.readdirSync(dir).filter((f) => f.endsWith('.html'))];
  for (const file of files) {
    const source = readStatic(file);
    assertTrue(!/direct reports/i.test(source), `${file}: "Direct reports"`);
    if (file === 'js/main.js' || file === 'js/function.js') {
      assertTrue(!/data-copy|ICONS\.copy|faq-copy|copyEmail/.test(source), `${file}: copy-e-mail code`);
    }
  }
});

test('the external dialog: name as title, badge, job, e-mail, city, country; nothing else', async () => {
  const globals = domGlobals({
    fetch: (url) => Promise.resolve({ ok: url.endsWith('/api/people/99/external'), status: 200,
      json: () => Promise.resolve({ name: 'Partner, Pat', email: 'pat@x.example', jobTitle: 'Managing Partner',
        city: 'London', country: 'United Kingdom', phone: '+000' }) }),
    Headers: class { set() {} }
  });
  const ctx = context(['ui.js', 'common.js'], globals);
  await ctx.run('openExternalPerson(99)');
  const dialog = globals.document.querySelector('[role=dialog]');
  assertEqual(dialog.querySelector('.ui-dialog-title').textContent, 'Partner, Pat');
  assertTrue(dialog.textContent.includes('Outside Central Services'), 'badge');
  assertEqual([...dialog.querySelectorAll('dt')].map((d) => d.textContent), ['Job title', 'Email', 'City', 'Country']);
  assertTrue(!dialog.textContent.includes('+000'), 'never a phone');
  assertTrue(!dialog.querySelector('.person-list') && !dialog.querySelector('[data-admin-edit]'), 'no counselees, no admin');
  assertEqual(dialog.querySelector('dd a').getAttribute('href'), 'mailto:pat@x.example');
});

test('the external dialog and the facts keep payloads as text', async () => {
  const globals = domGlobals({
    fetch: () => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ name: PAYLOAD, email: null,
      jobTitle: PAYLOAD, city: PAYLOAD, country: PAYLOAD }) }),
    Headers: class { set() {} }
  });
  const ctx = context(['ui.js', 'common.js'], globals);
  await ctx.run('openExternalPerson(5)');
  assertEqual(globals.document.querySelectorAll('img').length, 0, 'no element from a payload');
  const facts = String(context(['common.js']).run(`renderCardFacts({ city: ${JSON.stringify(PAYLOAD)}, country: 'x' })`));
  assertTrue(!facts.includes('<img') && facts.includes('&lt;img'), 'escaped');
});

done('cards check');
