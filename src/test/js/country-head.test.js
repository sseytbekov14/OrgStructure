// Phase 5d: country heads without the ", XX" title suffix. In the real AD the job titles carry no country suffix;
// in db mode a HEAD is a country head when the title suffix says so or (no suffix) the DTO countryCode is a
// non-KZ country code. Excel mode (rows without ids) keeps the suffix-only rule.
// Run: node src/test/js/country-head.test.js
const { context, readResource, runner, assertEqual, assertTrue } = require('./support/sandbox');

const { test, done } = runner();
const fn = context(['common.js', 'function.js']);
const code = (emp) => fn.run(`getCountryHeadCode(${JSON.stringify(emp)})`);

// db rows carry an id; Excel rows never do
const db = (extra) => ({ id: 1, group: 'HEAD', jobTitle: 'Head of Finance', ...extra });
const excel = (extra) => ({ group: 'HEAD', jobTitle: 'Head of Finance', ...extra });

test('db: suffix only', () => assertEqual(code(db({ jobTitle: 'Head of Finance, UZ' })), 'UZ'));
test('db: countryCode only', () => assertEqual(code(db({ countryCode: 'UZ' })), 'UZ'));
test('db: countryCode only, lower case', () => assertEqual(code(db({ countryCode: ' az ' })), 'AZ'));
test('db: both agree', () => assertEqual(code(db({ jobTitle: 'Head of Finance, GE', countryCode: 'GE' })), 'GE'));
test('db: both differ, the suffix wins', () =>
  assertEqual(code(db({ jobTitle: 'Head of Finance, AM', countryCode: 'UZ' })), 'AM'));
test('db: a KZ head is not a country head', () => assertEqual(code(db({ countryCode: 'KZ' })), null));
test('db: a non-head with a non-KZ country is not a country head', () =>
  assertEqual(code(db({ group: 'Service Desk', countryCode: 'UZ' })), null));
test('db: a non-head with a suffix is not a country head', () =>
  assertEqual(code(db({ group: 'UZ', jobTitle: 'Accountant, UZ', countryCode: 'UZ' })), null));
test('db: unknown countryCode is ignored', () => assertEqual(code(db({ countryCode: 'DE' })), null));
test('db: no countryCode and no suffix', () => assertEqual(code(db({})), null));
test('db: a suffix that is no country falls back to countryCode', () =>
  assertEqual(code(db({ jobTitle: 'Head of IT, PM', countryCode: 'GE' })), 'GE'));
test('db: group with spaces and lower case', () => assertEqual(code(db({ group: ' head ', countryCode: 'AM' })), 'AM'));

test('excel: suffix detected as before', () => assertEqual(code(excel({ jobTitle: 'Head of Finance, UZ' })), 'UZ'));
test('excel: KZ suffix kept as before', () => assertEqual(code(excel({ jobTitle: 'Head of Finance, KZ' })), 'KZ'));
test('excel: no suffix, no country head', () => assertEqual(code(excel({})), null));
test('excel: countryCode is never used without an id', () => assertEqual(code(excel({ countryCode: 'UZ' })), null));
test('excel: lower-case suffix is not a suffix (unchanged)', () =>
  assertEqual(code(excel({ jobTitle: 'Head of Finance, uz' })), null));
test('excel: non-head with suffix', () => assertEqual(code(excel({ group: 'UZ', jobTitle: 'Accountant, UZ' })), null));

// Render check with the mock data: F&O of the mock AD, with the UZ person (AD title without suffix) made a head
test('groups view: a db head from UZ without title suffix gets the UZ country column', () => {
  const ad = JSON.parse(readResource('mock/ad-employees.json'));
  const overrides = JSON.parse(readResource('mock/overrides.json')).filter((o) => o.cs === 'F&O');
  const byUpn = new Map(ad.map((r, i) => [r.userPrincipalName, { ...r, id: i + 1 }]));
  const country = (dn) => (dn.match(/OU=([A-Z]{2}),OU=Countries/) || [])[1] || null;
  const rows = overrides.map((o) => {
    const r = byUpn.get(o.upn);
    return { id: r.id, cs: 'F&O', name: r.displayName, email: r.mail, countryCode: country(r.distinguishedName),
      jobTitle: (r.title || '').replace(/,\s*[A-Z]{2}$/, ''),
      group: o.headRank ? 'HEAD' : (o.groupName || 'Office support') };
  });
  const uz = rows.find((r) => r.countryCode === 'UZ');
  assertTrue(uz, 'the mock has a UZ person in F&O');
  uz.group = 'HEAD';
  assertTrue(!/,\s*[A-Z]{2}$/.test(uz.jobTitle), 'the AD-like title carries no suffix');

  const html = String(fn.run(`renderGroupsView(${JSON.stringify(rows)}.map((e, i) => ({ ...e, _id: i })))`))
    .replace(/\s+/g, ' ');
  const headSection = html.slice(0, html.indexOf('section-block'));
  assertTrue(/col-header country[^>]*>\s*<span class="col-title">UZ<\/span>/.test(html), 'UZ country column present');
  assertTrue(!headSection.includes(uz.name), 'the UZ head is not among the function heads');
  rows.filter((r) => r.group === 'HEAD' && r !== uz).forEach((h) =>
    assertTrue(headSection.includes(h.name), `${h.name} stays a function head`));
});

test('groups view: excel rows render as before (suffix heads in their country column)', () => {
  const rows = [
    { name: 'Main, Head', group: 'HEAD', jobTitle: 'Head of Finance' },
    { name: 'Country, Head', group: 'HEAD', jobTitle: 'Head of Finance, UZ', countryCode: 'UZ' },
    { name: 'Staff, Uz', group: 'UZ', jobTitle: 'Accountant, UZ' },
    { name: 'Other, Head', group: 'HEAD', jobTitle: 'Deputy Head', countryCode: 'GE' }
  ];
  const html = String(fn.run(`renderGroupsView(${JSON.stringify(rows)}.map((e, i) => ({ ...e, _id: i })))`));
  const headSection = html.slice(0, html.indexOf('section-block'));
  assertTrue(headSection.includes('Main, Head') && headSection.includes('Other, Head'), 'function heads');
  assertTrue(!headSection.includes('Country, Head'), 'suffix head not a function head');
  assertTrue(!/col-title">GE</.test(html), 'no GE column from countryCode in excel mode');
});

done('country head check');
