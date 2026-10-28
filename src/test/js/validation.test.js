// U3: static/js/validation.js against the shared vectors (src/test/resources/validation-vectors.json); FieldRulesTest
// runs the same vectors through FieldRules.java, so both sides normalize, refuse and word messages identically.
// Run: node src/test/js/validation.test.js
const fs = require('fs');
const path = require('path');
const { context, runner, assertEqual } = require('./support/sandbox');

const { test, done } = runner();
const data = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'resources', 'validation-vectors.json'), 'utf8'));
const ctx = context(['validation.js']);
const rules = ctx.run('FIELD_RULES');

function build(spec) {
  if (typeof spec === 'string' || spec == null) return spec;
  return spec.repeat[0].repeat(spec.repeat[1]) + (spec.suffix || '');
}

function inputOf(v) {
  return v.repeat ? build({ repeat: v.repeat, suffix: v.suffix }) : v.input;
}

function expectedOf(v, input) {
  if (v.valid) return input;
  if (v.normalizedRepeat) return v.normalizedRepeat[0].repeat(v.normalizedRepeat[1]) + (v.normalizedSuffix || '');
  return v.normalized;
}

test('rules match the vectors file', () => {
  for (const [field, rule] of Object.entries(data.rules)) {
    const mine = rules[field];
    assertEqual([mine.label, mine.min, mine.max, mine.required], [rule.label, rule.min, rule.max, rule.required], field);
  }
});

data.vectors.forEach((v, i) => {
  const input = inputOf(v);
  const label = `#${i} ${v.field} ${JSON.stringify(input).slice(0, 40)}`;
  test(label, () => {
    const result = ctx.run(`validateField(FIELD_RULES[${JSON.stringify(v.field)}], ${JSON.stringify(input)})`);
    if (v.code) {
      assertEqual(result.problem && result.problem.code, v.code, 'refused with');
    } else {
      assertEqual(result.problem, null, 'valid');
      assertEqual(result.value, expectedOf(v, input), 'normalized');
    }
  });
});

for (const [code, [field, raw]] of Object.entries(data.messageInputs)) {
  test(`message ${code}`, () => {
    const problem = ctx.run(`checkField(FIELD_RULES[${JSON.stringify(field)}], normalizeField(${JSON.stringify(build(raw))}))`);
    assertEqual(problem.code, code);
    assertEqual(problem.message, data.messages[code]);
  });
}

test('the counter counts characters as the rules do', () => {
  assertEqual(ctx.run(`fieldLength('  e\\u0301  x ')`), 3);
  assertEqual(ctx.run(`fieldLength('')`), 0);
});

done('validation vectors check');
