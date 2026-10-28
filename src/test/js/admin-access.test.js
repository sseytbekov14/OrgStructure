// U1: admin.js and the header "Admin" link exist only for admins (GET /api/me/roles admin=true). For everybody else
// no admin control and no admin script is ever added to the page.
// Run: node src/test/js/admin-access.test.js
const { context, readStatic, runner, assertEqual, assertTrue } = require('./support/sandbox');

const { test, done } = runner();

/** A tiny DOM: enough for the loader (createElement, querySelector by class / attribute, append) */
function miniDocument() {
  const appended = [];
  const header = { children: [], appendChild(node) { this.children.push(node); appended.push(node); },
    querySelector(selector) { return this.children.find((c) => selector === `.${c.className}`) || null; } };
  const body = { dataset: {}, children: [], appendChild(node) { this.children.push(node); appended.push(node); } };
  const document = {
    cookie: '', body, appended,
    createElement(tag) {
      const attributes = {};
      return { tag, className: '', textContent: '', attributes,
        setAttribute(name, value) { attributes[name] = value; } };
    },
    querySelector(selector) {
      if (selector === '.header-inner') return header;
      if (selector === 'script[data-admin-tools]') return appended.find((n) => n.tag === 'script') || null;
      return null;
    },
    getElementById: () => null,
    addEventListener: () => {},
    removeEventListener: () => {}
  };
  return document;
}

function page(me) {
  const document = miniDocument();
  const fetch = () => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(me) });
  const ctx = context(['common.js'], { document, fetch, Headers: class { set() {} } });
  return { ctx, document };
}

async function settle(ctx) {
  await ctx.run('Me.loading');
  await new Promise((resolve) => setImmediate(resolve));
}

test('non-admin: no admin script and no admin link', async () => {
  const { ctx, document } = page({ authenticated: true, you: 'reader', roles: ['USER'], admin: false,
    superAdmin: false, editingEnabled: true });
  await settle(ctx);
  assertEqual(document.appended.length, 0, 'nothing added to the page');
});

test('anonymous / open site: nothing added', async () => {
  const { ctx, document } = page({ authenticated: false, you: null, roles: [], admin: false, superAdmin: false,
    editingEnabled: false });
  await settle(ctx);
  assertEqual(document.appended.length, 0, 'nothing added to the page');
});

test('roles request failing: nothing added', async () => {
  const document = miniDocument();
  const fetch = () => Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve({}) });
  const ctx = context(['common.js'], { document, fetch, Headers: class { set() {} } });
  await settle(ctx);
  assertEqual(document.appended.length, 0, 'nothing added to the page');
});

test('admin: admin.js loaded once and the Admin link shown', async () => {
  const { ctx, document } = page({ authenticated: true, you: 'editor@corp.example', roles: ['USER'], admin: true,
    superAdmin: false, editingEnabled: true });
  await settle(ctx);
  const scripts = document.appended.filter((n) => n.tag === 'script');
  assertEqual(scripts.map((s) => s.src.replace(/\?.*$/, '')), ['/js/validation.js', '/js/admin.js', '/js/admin-groups.js', '/js/admin-faq.js'], 'the admin scripts in order');
  assertTrue(scripts.every((s) => s.async === false), 'kept in order');
  const links = document.appended.filter((n) => n.tag === 'a');
  assertEqual(links.map((l) => [l.href, l.textContent]), [['/admin.html', 'Admin']]);
  await ctx.run('initAdminTools()');
  assertEqual(document.appended.filter((n) => n.tag === 'script').length, 4, 'not loaded twice');
});

test('the admin page itself loads no admin.js', async () => {
  const document = miniDocument();
  document.body.dataset.page = 'admin';
  const fetch = () => Promise.resolve({ ok: true, json: () => Promise.resolve({ admin: true }) });
  context(['common.js'], { document, fetch, Headers: class { set() {} } });
  await new Promise((resolve) => setImmediate(resolve));
  assertEqual(document.appended.length, 0, 'nothing added by common.js on admin.html');
});

test('pages carry no static admin.js tag', () => {
  for (const html of ['index.html', 'function.html']) {
    assertTrue(!readStatic(html).includes('admin.js'), `${html} has no admin.js script tag`);
  }
});

test('admin.js reads admin=true, not the SYSTEM_ADMIN role', () => {
  const admin = readStatic('js/admin.js');
  assertTrue(admin.includes('me.admin === true'), 'admin flag used');
  assertTrue(!admin.includes('/api/admin/'), 'no old /api/admin paths');
});

done('admin access check');
