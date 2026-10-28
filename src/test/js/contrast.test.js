// U2: WCAG 2.x contrast of every text/background token pair used by the design system (tokens.css): at least 4.5
// for text, 3.0 for focus indicators and borders of controls. Changing a token that breaks a pair fails here.
// Run: node src/test/js/contrast.test.js
const { readStatic, runner, assertTrue } = require('./support/sandbox');

const { test, done } = runner();
const css = readStatic('css/tokens.css');

/** The --color-* tokens of :root, references (var(--x)) resolved */
function tokens() {
  const root = css.slice(css.indexOf(':root'), css.indexOf('}', css.indexOf(':root')));
  const raw = {};
  for (const m of root.matchAll(/(--color-[\w-]+)\s*:\s*([^;]+);/g)) raw[m[1]] = m[2].trim();
  const resolve = (value, depth = 0) => {
    const ref = value.match(/^var\((--[\w-]+)\)$/);
    if (!ref) return value;
    if (depth > 5 || !(ref[1] in raw)) throw new Error(`unresolved ${value}`);
    return resolve(raw[ref[1]], depth + 1);
  };
  return Object.fromEntries(Object.entries(raw).map(([k, v]) => [k, resolve(v)]));
}

function rgb(hex) {
  const h = hex.replace('#', '');
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h.slice(0, 6);
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16));
}

function luminance(hex) {
  const [r, g, b] = rgb(hex).map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function ratio(a, b) {
  const [l1, l2] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (l1 + 0.05) / (l2 + 0.05);
}

const T = tokens();

// [foreground, background, minimum]
const TEXT = 4.5;
const UI = 3.0;
const PAIRS = [
  ['--color-text', '--color-bg', TEXT],
  ['--color-text', '--color-surface', TEXT],
  ['--color-text', '--color-surface-alt', TEXT],
  ['--color-text-secondary', '--color-bg', TEXT],
  ['--color-text-secondary', '--color-surface', TEXT],
  ['--color-text-secondary', '--color-surface-alt', TEXT],
  ['--color-text-muted', '--color-surface', TEXT],
  ['--color-text-muted', '--color-bg', TEXT],
  ['--color-text-muted', '--color-surface-alt', TEXT],
  ['--color-link', '--color-surface', TEXT],
  ['--color-link', '--color-bg', TEXT],
  ['--color-brand', '--color-surface', TEXT],
  ['--color-brand', '--color-surface-alt', TEXT],
  ['--color-brand', '--color-accent-soft', TEXT],
  ['--color-on-brand', '--color-brand', TEXT],
  ['--color-on-brand', '--color-brand-hover', TEXT],
  ['--color-on-brand', '--color-brand-strong', TEXT],
  ['--color-on-brand', '--color-accent', TEXT],
  ['--color-on-brand', '--color-cobalt', TEXT],
  ['--color-on-brand', '--color-purple', TEXT],
  ['--color-white', '--color-danger', TEXT],
  ['--color-white', '--color-danger-hover', TEXT],
  ['--color-white', '--color-success', TEXT],
  ['--color-white', '--color-black', TEXT],
  ['--color-danger', '--color-surface', TEXT],
  ['--color-danger', '--color-danger-soft', TEXT],
  ['--color-success', '--color-success-soft', TEXT],
  ['--color-warning', '--color-warning-soft', TEXT],
  ['--color-info', '--color-info-soft', TEXT],
  ['--color-text', '--color-highlight', TEXT],
  ['--color-text', '--color-gray-200', TEXT],
  ['--color-focus', '--color-surface', UI],
  ['--color-focus', '--color-bg', UI],
  ['--color-border-strong', '--color-surface', UI],
  ['--color-brand', '--color-avatar-0', TEXT],
  ['--color-brand', '--color-avatar-1', TEXT],
  ['--color-brand', '--color-avatar-2', TEXT],
  ['--color-brand', '--color-avatar-3', TEXT],
  ['--color-brand', '--color-avatar-4', TEXT],
  ['--color-brand', '--color-avatar-5', TEXT]
];

for (const [fg, bg, min] of PAIRS) {
  test(`${fg} on ${bg} >= ${min}`, () => {
    assertTrue(T[fg] && T[bg], `tokens ${fg} / ${bg} exist`);
    const value = ratio(T[fg], T[bg]);
    assertTrue(value >= min, `${T[fg]} on ${T[bg]} is ${value.toFixed(2)}`);
  });
}

test('white text is never put on light blue (documented exception)', () => {
  assertTrue(ratio(T['--color-white'], T['--color-light-blue']) < TEXT, 'light blue is an accent only');
});

test('the brand palette of the prompts is in place', () => {
  const expected = { '--color-kpmg-blue': '#00338d', '--color-medium-blue': '#005eb8', '--color-light-blue': '#0091da',
    '--color-cobalt': '#1e49e2', '--color-purple': '#483698' };
  for (const [k, v] of Object.entries(expected)) assertTrue(T[k].toLowerCase() === v, `${k} = ${v}`);
  assertTrue(/--font-family:\s*Univers, "Segoe UI", Arial, sans-serif;/.test(css), 'font stack');
});

done('contrast check');
