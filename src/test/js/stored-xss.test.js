// Stored XSS check of the page scripts (Phase 5b): the texts admins edit (group, area of duties, job description)
// and the AD fields are shown to every user; each renderer must turn a payload into text, never into a tag.
// Run: node src/test/js/stored-xss.test.js   (StoredXssTest runs it when node is installed; exit code 1 = failure)
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const JS = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js');
// A tag, and a quote that would break out of an attribute value
const PAYLOAD = '<img src=x onerror=alert(1)>" onfocus="alert(2)';
const ESCAPED = '&lt;img src=x onerror=alert(1)&gt;&quot; onfocus=&quot;alert(2)';

/** Anything not set answers with a harmless stub; set values are kept (innerHTML of fake elements) */
function fake() {
  const values = {};
  return new Proxy(function () {}, {
    get: (target, key) => {
      if (key in values) return values[key];
      if (key === Symbol.toPrimitive) return () => '';
      if (key === 'then') return undefined;
      if (key === 'querySelectorAll' || key === 'getElementsByClassName') return () => [];
      return fake();
    },
    set: (target, key, value) => { values[key] = value; return true; },
    apply: () => fake(),
    construct: () => fake()
  });
}

function context(files) {
  const elements = {};
  const document = fake();
  document.getElementById = (id) => (elements[id] = elements[id] || fake());
  const sandbox = { console, Map, Set, URLSearchParams, JSON, Math, Date, Promise, String, Number, RegExp, Array,
    Object, setTimeout: () => 0, clearTimeout: () => {}, requestAnimationFrame: () => 0, document,
    localStorage: fake(), sessionStorage: fake(), navigator: fake(), location: fake(), history: fake(),
    fetch: () => new Promise(() => {}), EventSource: fake(), getComputedStyle: fake(), matchMedia: fake(),
    ResizeObserver: fake(), MutationObserver: fake(), AbortController: fake(), Headers: fake() };
  sandbox.window = sandbox;
  sandbox.addEventListener = () => {};
  sandbox.removeEventListener = () => {};
  vm.createContext(sandbox);
  for (const file of files) {
    vm.runInContext(fs.readFileSync(path.join(JS, file), 'utf8'), sandbox, { filename: file });
  }
  return { run: (code) => vm.runInContext(code, sandbox), elements };
}

const evil = {
  id: 7, parentId: null, countryCode: 'KZ', cs: 'ITS',
  name: `Doe, ${PAYLOAD}`, email: 'doe@kz.example', jobTitle: `Engineer ${PAYLOAD}`, group: `Team ${PAYLOAD}`,
  areaOfDuties: `Duties ${PAYLOAD}`, jobDescription: `Description ${PAYLOAD}`, pm: `Boss ${PAYLOAD}`,
  pmEmail: 'boss@kz.example', pmJobTitle: `Chief ${PAYLOAD}`, photoUrl: '/photos/default.jpg', gradeOrder: 6
};
const evilHead = { ...evil, id: 8, group: 'HEAD', name: `Head ${PAYLOAD}` };
const evilCountry = { ...evil, id: 9, group: 'UZ', name: `Country ${PAYLOAD}` };

const failures = [];
function check(label, html) {
  const text = String(html);
  if (process.env.XSS_DEBUG) console.log(`--- ${label}\n${text.replace(/\s+/g, ' ')}`);
  if (text.includes('<img src=x')) {
    failures.push(`${label}: the payload became a tag`);
  } else if (text.includes('" onfocus="')) {
    failures.push(`${label}: the payload broke out of an attribute`);
  } else if (!text.includes(ESCAPED)) {
    failures.push(`${label}: the payload is not in the output (escaped) - renderer not covered`);
  } else {
    console.log(`ok   ${label}`);
  }
}

// function page
const fn = context(['common.js', 'function.js']);
const data = JSON.stringify([evil, evilHead, evilCountry]);
fn.run(`setDirectory(${data});`);
check('profile card', fn.run(`renderProfile(${JSON.stringify(evil)})`));
check('profile card of a manager row', fn.run(`renderPersonRow(${JSON.stringify(evil)})`));
check('employee card', fn.run(`renderEmpCard({ ...${JSON.stringify(evil)}, _id: 0 })`));
check('head card', fn.run(`renderHeadCard({ ...${JSON.stringify(evilHead)}, _id: 1 })`));
check('group columns', fn.run(`renderGroupsView(${data}.map((e, i) => ({ ...e, _id: i })))`));
check('main head card (U3)', fn.run(`renderHeadCard({ ...${JSON.stringify(evilHead)}, _id: 1 }, 'main')`));
check('additional head card (U3)', fn.run(`renderHeadCard({ ...${JSON.stringify(evilHead)}, _id: 1 }, 'additional')`));
check('additional head in the tree (U3)', fn.run(`renderEmpCard({ ...${JSON.stringify(evil)}, _id: 0 }, { additionalHead: true })`));
check('groups view with two heads (U3)', fn.run(`renderGroupsView([${JSON.stringify(evilHead)}, { ...${JSON.stringify(evilHead)}, id: 10 }].map((e, i) => ({ ...e, _id: i })))`));
check('tree label (group of a child)', fn.run(`escapeHtml(slotLabel({ emp: { group: 'Other' } }, { emp: ${JSON.stringify(evil)} }))`));
check('search highlight', fn.run(`highlightText(${JSON.stringify(evil.areaOfDuties)}, 'duties')`));

// start page search
const main = context(['common.js', 'main.js']);
main.run(`setDirectory(${data}); searchResults = ${data}; searchQuery = 'description'; functionFilter = 'All';`);
main.run('renderSearchResults();');
check('search results', main.elements.searchResults.innerHTML);
check('search match context', main.run(`renderMatchContext(${JSON.stringify(evil)}, 'description')`));

if (failures.length) {
  failures.forEach((f) => console.error(`FAIL ${f}`));
  process.exit(1);
}
console.log('stored XSS check passed');
