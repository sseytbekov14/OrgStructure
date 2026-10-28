// A function page for the admin tests: ui.js, common.js, validation.js and admin.js on the minimal DOM, with a fake
// fetch whose answers the test sets per "METHOD path" (query string ignored) and which records every request.
const { context } = require('./sandbox');
const { domGlobals, Event } = require('./mini-dom');

function adminPage(routes = {}) {
  const requests = [];
  const answers = {
    'GET /api/me/roles': { status: 200, body: { authenticated: true, you: 'admin', roles: ['USER'], admin: true, superAdmin: false, editingEnabled: true } },
    ...routes
  };
  const fetch = (url, options = {}) => {
    const method = (options.method || 'GET').toUpperCase();
    const path = url.replace(/\?.*$/, '');
    const request = { method, url, path, body: options.body ? JSON.parse(options.body) : null };
    requests.push(request);
    let answer = answers[`${method} ${path}`];
    if (typeof answer === 'function') answer = answer(request);
    if (Array.isArray(answer)) answer = answer.length > 1 ? answer.shift() : answer[0];
    const { status = 200, body = {} } = answer || { status: 404, body: { message: 'not found' } };
    return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(JSON.parse(JSON.stringify(body))) });
  };
  class Headers { constructor() {} set() {} }
  const globals = domGlobals({ fetch, Headers });
  const chart = globals.document.createElement('div');
  chart.id = 'orgChart';
  globals.document.body.append(chart);
  const ctx = context(['ui.js', 'common.js', 'validation.js', 'admin.js', 'admin-groups.js'], globals);
  ctx.run('function debounce(fn) { return fn; }');
  ctx.run('var refreshes = 0; window.refreshOrgChart = async () => { refreshes++; };');
  const { document } = globals;
  return {
    ctx, document, requests, answers, globals,
    dialogs: () => document.querySelectorAll('[role=dialog]'),
    topDialog: () => { const d = document.querySelectorAll('[role=dialog]'); return d[d.length - 1] || null; },
    button: (root, text) => [...root.querySelectorAll('button')].find((b) => b.textContent === text) || null,
    type: (input, value) => { input.value = value; input.dispatchEvent(new Event('input')); },
    writes: () => requests.filter((r) => r.method !== 'GET')
  };
}

/** Lets pending promises (fetch answers, dialogs) settle */
async function settle(times = 6) {
  for (let i = 0; i < times; i++) await new Promise((resolve) => setImmediate(resolve));
}

module.exports = { adminPage, settle };
