// Shared helpers of the node tests of the page scripts (no browser): load static/js files into a vm sandbox with a
// stub DOM, and collect failures. Every *.test.js prints "<name> passed" and exits 1 on failure (JsTestsTest runs them).
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const RESOURCES = path.join(__dirname, '..', '..', '..', 'main', 'resources');
const JS = path.join(RESOURCES, 'static', 'js');

/** Anything not set answers with a harmless stub; set values are kept */
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

/** Loads the given scripts into one sandbox; extra globals (e.g. a real document) override the stubs */
function context(files, globals = {}) {
  const elements = {};
  const document = fake();
  document.getElementById = (id) => (elements[id] = elements[id] || fake());
  const sandbox = { console, Map, Set, WeakMap, URLSearchParams, JSON, Math, Date, Promise, String, Number, RegExp,
    Array, Object, Error, setTimeout: () => 0, clearTimeout: () => {}, requestAnimationFrame: () => 0, document,
    localStorage: fake(), sessionStorage: fake(), navigator: fake(), location: fake(), history: fake(),
    fetch: () => new Promise(() => {}), EventSource: fake(), getComputedStyle: fake(), matchMedia: fake(),
    ResizeObserver: fake(), MutationObserver: fake(), AbortController: fake(), Headers: fake(), ...globals };
  sandbox.window = sandbox;
  sandbox.addEventListener = sandbox.addEventListener || (() => {});
  sandbox.removeEventListener = sandbox.removeEventListener || (() => {});
  vm.createContext(sandbox);
  for (const file of files) {
    vm.runInContext(fs.readFileSync(path.join(JS, file), 'utf8'), sandbox, { filename: file });
  }
  return { run: (code) => vm.runInContext(code, sandbox), elements, sandbox };
}

function readResource(relative) {
  return fs.readFileSync(path.join(RESOURCES, relative), 'utf8');
}

function readStatic(relative) {
  return fs.readFileSync(path.join(RESOURCES, 'static', relative), 'utf8');
}

/** Minimal test runner: test(name, fn) records results; done(suite) prints them and sets the exit code */
function runner() {
  const failures = [];
  const pending = [];
  function test(name, fn) {
    pending.push((async () => {
      try {
        await fn();
        console.log(`ok   ${name}`);
      } catch (e) {
        failures.push(`${name}: ${e && e.message ? e.message : e}`);
      }
    })());
  }
  async function done(suite) {
    await Promise.all(pending);
    if (failures.length) {
      failures.forEach((f) => console.error(`FAIL ${f}`));
      process.exit(1);
    }
    console.log(`${suite} passed`);
  }
  return { test, done };
}

function assertEqual(actual, expected, message) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) throw new Error(`${message || 'values differ'}: expected ${e}, got ${a}`);
}

function assertTrue(value, message) {
  if (!value) throw new Error(message || 'expected true');
}

module.exports = { context, fake, readResource, readStatic, runner, assertEqual, assertTrue, JS, RESOURCES };
