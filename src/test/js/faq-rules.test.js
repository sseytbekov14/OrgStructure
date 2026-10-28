// U5: the FAQ part of static/js/validation.js against the shared vectors (faq-vectors.json); FaqRulesTest runs the
// same vectors through FaqRules.java.
// Run: node src/test/js/faq-rules.test.js
const fs = require('fs');
const path = require('path');
const { context, runner, assertEqual, assertTrue } = require('./support/sandbox');

const { test, done } = runner();
const data = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'resources', 'faq-vectors.json'), 'utf8'));
const ctx = context(['validation.js']);

const expand = (value) => value == null ? null
  : value.replace(/\{U\+([0-9A-F]{4})\}|\{NL\}/g, (m, hex) => (hex ? String.fromCharCode(parseInt(hex, 16)) : '\n'));
const input = (v) => (v.repeat ? v.repeat[0].repeat(v.repeat[1]) : expand(v.input));
// Inside JSON text the tokens become JSON escapes (a raw line break would not be valid JSON)
const BS = String.fromCharCode(92);
const deepExpand = (value) => JSON.parse(JSON.stringify(value)
  .replace(/\{U\+([0-9A-F]{4})\}|\{NL\}/g, (m, hex) => (hex ? BS + 'u' + hex : BS + 'n')));

test('the limits match the vectors file', () => {
  const limits = ctx.run('FAQ_LIMITS');
  for (const key of Object.keys(data.limits)) assertEqual(limits[key], data.limits[key], key);
});

data.vectors.forEach((v, i) => {
  test(`#${i} ${v.kind}`, () => {
    const raw = input(v);
    const fnName = v.kind === 'text' ? 'normalizeFaqText' : 'normalizeFaqLine';
    const normalized = ctx.run(`${fnName}(${JSON.stringify(raw)})`);
    const problem = v.kind === 'email'
      ? ctx.run(`checkFaqEmail(${JSON.stringify(normalized)})`)
      : ctx.run(`checkFaqText('Field', ${JSON.stringify(normalized)}, ${v.max}, ${!!v.required}, ${v.kind === 'text'})`);
    if (v.code) {
      assertEqual(problem && problem.code, v.code, 'refused with');
    } else {
      assertEqual(problem, null, 'valid');
      assertEqual(normalized, v.valid ? raw : expand(v.normalized), 'normalized');
    }
  });
});

function big(sections, items) {
  return { sections: Array.from({ length: sections }, (_, s) => ({ kind: 'GUIDELINES', title: `Section ${s}`,
    items: Array.from({ length: items }, (__, i) => ({ text: `Line ${i}` })) })) };
}

data.documents.forEach((d) => {
  test(`document: ${d.name}`, () => {
    const document = d.sectionsRepeat ? big(d.sectionsRepeat, d.itemsRepeat) : deepExpand(d.input);
    const result = ctx.run(`validateFaqDocument(${JSON.stringify(document)})`);
    const codes = Object.fromEntries(Object.entries(result.errors).map(([p, e]) => [p, e.code]));
    if (d.sectionsRepeat) {
      Object.entries(d.errors).forEach(([p, code]) => assertEqual(codes[p], code, p));
    } else {
      assertEqual(Object.keys(codes).sort(), Object.keys(d.errors).sort(), 'error paths');
      Object.entries(d.errors).forEach(([p, code]) => assertEqual(codes[p], code, p));
    }
  });
});

test('a valid document is normalized like the server does', () => {
  const result = ctx.run(`validateFaqDocument(${JSON.stringify(deepExpand(data.documents[0].input))})`);
  assertEqual(result.document.sections[0].kind, 'GUIDELINES');
  assertEqual(result.document.sections[0].title, 'Request rules');
  assertEqual(result.document.sections[1].items[1].email, null);
  assertEqual(result.document.sections[2].items[0].answer, 'Like this.\nAnd that.');
  assertTrue(Object.keys(result.errors).length === 0);
});

done('faq rules check');
