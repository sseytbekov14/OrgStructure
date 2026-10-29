// U5: the FAQ panel from the API (DOM only: links only https:// / mailto: with rel="noopener noreferrer", payloads stay
// text, long texts and many items) and the admin editor (add / remove / reorder, inline errors, preview, save with
// the version, no-op, dirty close, conflict keeping the draft).
// Run: node src/test/js/faq.test.js
const { context, runner, assertEqual, assertTrue } = require('./support/sandbox');
const { domGlobals, Event, key } = require('./support/mini-dom');

const { test, done } = runner();
const PAYLOAD = '<img src=x onerror=alert(1)>';

const DOC = { sections: [
  { kind: 'GUIDELINES', title: 'Request rules', items: [{ text: 'Use https://portal.example/forms. Or mail mailto:it@kpmg.kz!' }] },
  { kind: 'MAILBOXES', title: 'Team mailboxes', items: [{ label: 'Service Desk', email: 'it@kpmg.kz' }, { label: '1C team', email: null }] },
  { kind: 'QUESTIONS', title: 'Questions', items: [{ question: 'How?', answer: 'Line one\nLine two' }] }
] };

function page(routes = {}, admin = true) {
  const requests = [];
  const answers = {
    'GET /api/me/roles': { body: { authenticated: true, admin, superAdmin: false, editingEnabled: true } },
    'GET /api/functions/ITS/faq': { body: { cs: 'ITS', version: 4, content: DOC } },
    ...routes
  };
  const fetch = (url, options = {}) => {
    const method = (options.method || 'GET').toUpperCase();
    const path = url.replace(/\?.*$/, '');
    requests.push({ method, path, body: options.body ? JSON.parse(options.body) : null });
    let answer = answers[`${method} ${path}`];
    if (Array.isArray(answer)) answer = answer.length > 1 ? answer.shift() : answer[0];
    const { status = 200, body = {} } = answer || { status: 404, body: {} };
    return Promise.resolve({ ok: status < 300, status, json: () => Promise.resolve(JSON.parse(JSON.stringify(body))) });
  };
  const globals = domGlobals({ fetch, Headers: class { set() {} } });
  const { document } = globals;
  for (const id of ['faqBanner', 'funcCount', 'funcTitle', 'funcSearchCount', 'orgChart', 'treeEdges', 'treeLabels',
    'treeOverlay', 'treeStage', 'treeViewport', 'treeZoomValue', 'funcSearchNext', 'funcSearchPrev']) {
    const el = document.createElement(id.startsWith('funcSearch') && id !== 'funcSearchCount' ? 'button' : 'div');
    el.id = id;
    document.body.append(el);
  }
  const search = document.createElement('input');
  search.id = 'funcSearch';
  document.body.append(search);
  const ctx = context(['ui.js', 'common.js', 'function.js', 'validation.js', 'admin.js', 'admin-faq.js'], globals);
  return { ctx, document, requests, answers, writes: () => requests.filter((r) => r.method !== 'GET') };
}

async function settle(times = 8) {
  for (let i = 0; i < times; i++) await new Promise((resolve) => setImmediate(resolve));
}

async function rendered(routes, admin) {
  const p = page(routes, admin);
  await p.ctx.run(`renderFaqBanner('ITS')`);
  await settle();
  return { ...p, card: p.document.getElementById('faqCard') };
}

const button = (root, text) => [...root.querySelectorAll('button')].find((b) => b.textContent === text) || null;
const type = (input, value) => { input.value = value; input.dispatchEvent(new Event('input')); };

// --- public panel ----------------------------------------------------------------------------------------------

test('the panel shows the sections of the API in order: guidelines, mailboxes, questions', async () => {
  const { card } = await rendered();
  assertEqual([...card.querySelectorAll('.faq-section-title')].map((h) => h.textContent),
    ['Request rules', 'Team mailboxes', 'Questions']);
  assertTrue(card.querySelector('.faq-mailbox-missing').textContent === 'Email to be added', 'missing address');
  const address = card.querySelector('.faq-mailbox-email');
  assertEqual(address.tagName, 'SPAN', 'the address is plain text');
  assertEqual(address.textContent, 'it@kpmg.kz');
  const mail = card.querySelectorAll('.faq-mailbox-mail');
  assertEqual(mail.length, 1, 'a mail link for the mailbox with an address only');
  assertEqual(mail[0].tagName, 'A', 'the envelope on the left is the link');
  assertTrue(mail[0].classList.contains('faq-mailbox-icon'), 'no separate button: the envelope itself');
  assertEqual(mail[0].getAttribute('href'), 'mailto:it@kpmg.kz', 'mailto on the envelope');
  assertEqual(card.querySelectorAll('.faq-mailbox .icon-btn').length, 0, 'no mail button on the right any more');
  assertEqual(card.querySelector('.faq-mailbox-missing').closest('.faq-mailbox').querySelector('.faq-mailbox-icon').tagName,
    'SPAN', 'without an address the envelope is no link');
  assertEqual(mail[0].getAttribute('aria-label'), 'Email Service Desk (it@kpmg.kz)');
  assertEqual(mail[0].title, 'Email it@kpmg.kz');
  assertEqual(card.querySelector('.faq-answer').textContent, 'Line one\nLine two');
  assertTrue(!card.querySelector('[data-faq-copy]'), 'no copy button any more');
});

test('links: only https:// and mailto:, built as elements with rel noopener noreferrer, trailing punctuation outside', async () => {
  const { card } = await rendered();
  const links = [...card.querySelector('.faq-rule-text').querySelectorAll('a')];
  assertEqual(links.map((a) => a.getAttribute('href')), ['https://portal.example/forms', 'mailto:it@kpmg.kz']);
  links.forEach((a) => assertEqual(a.getAttribute('rel'), 'noopener noreferrer'));
  assertEqual(links[0].getAttribute('target'), '_blank');
  assertEqual(card.querySelector('.faq-rule-text').textContent, 'Use https://portal.example/forms. Or mail it@kpmg.kz!');
});

test('payloads and other schemes stay text (stored XSS)', async () => {
  const evil = { sections: [
    { kind: 'GUIDELINES', title: PAYLOAD, items: [{ text: `${PAYLOAD} javascript:alert(1) http://x.example` }] },
    { kind: 'MAILBOXES', title: 'm', items: [{ label: PAYLOAD, email: 'javascript:alert(1)' }] },
    { kind: 'QUESTIONS', title: 'q', items: [{ question: PAYLOAD, answer: `${PAYLOAD}\ndata:text/html,x` }] }
  ] };
  const { card } = await rendered({ 'GET /api/functions/ITS/faq': { body: { cs: 'ITS', version: 1, content: evil } } });
  assertEqual(card.querySelectorAll('img').length, 0, 'no element from a payload');
  assertEqual(card.querySelectorAll('a').length, 0, 'no link for other schemes');
  assertTrue(card.textContent.includes(PAYLOAD), 'shown as text');
});

test('long texts and many items are all rendered', async () => {
  const items = Array.from({ length: 30 }, (_, i) => ({ question: `Q${i}`, answer: 'x'.repeat(2000) }));
  const { card } = await rendered({ 'GET /api/functions/ITS/faq': { body: { cs: 'ITS', version: 1,
    content: { sections: [{ kind: 'QUESTIONS', title: 't'.repeat(80), items }] } } } });
  assertEqual(card.querySelectorAll('.faq-answer').length, 30);
  assertEqual(card.querySelector('.faq-answer').textContent.length, 2000);
});

test('no FAQ: the panel is hidden; non-admins get no Edit button', async () => {
  const none = await rendered({ 'GET /api/functions/ITS/faq': { status: 404, body: {} } });
  assertEqual(none.document.getElementById('faqBanner').style.display, 'none');
  const reader = await rendered({}, false);
  assertTrue(!reader.card.querySelector('[data-faq-edit]'), 'no edit control for non-admins');
});

// --- editor ---------------------------------------------------------------------------------------------------

async function editor(routes) {
  const p = await rendered(routes);
  const edit = p.card.querySelector('[data-faq-edit]');
  assertTrue(edit, 'admins get Edit FAQ');
  edit.click();
  await settle();
  const root = () => p.document.querySelector('.faq-editor');
  return { ...p, root, field: (path) => root().querySelector(`[data-path="${path}"]`) };
}

test('editor: every field with its label and counter, Save disabled until changed', async () => {
  const { root, field } = await editor();
  const title = field('sections[0].title');
  assertEqual(title.value, 'Request rules');
  assertEqual(root().querySelector(`label[for="${title.id}"]`).textContent, 'Section title');
  assertTrue(root().querySelector('[data-faq-save]').disabled, 'nothing changed');
  type(field('sections[2].items[0].answer'), 'New answer');
  assertTrue(!root().querySelector('[data-faq-save]').disabled, 'changed');
});

test('editor: add, reorder and remove sections and items', async () => {
  const { root, ctx } = await editor();
  button(root(), 'Add question').click();
  await settle();
  assertEqual(ctx.run('FaqEditor.draft.sections[2].items.length'), 2);
  const down = [...root().querySelectorAll('button')].find((b) => b.getAttribute('aria-label') === 'Move section Request rules down');
  down.click();
  assertEqual(ctx.run('FaqEditor.draft.sections.map((s) => s.kind).join()'), 'MAILBOXES,GUIDELINES,QUESTIONS');
  root().querySelector('.faq-editor-kind').value = 'QUESTIONS';
  button(root(), 'Add section').click();
  assertEqual(ctx.run('FaqEditor.draft.sections.length'), 4);
  const removeItem = [...root().querySelectorAll('button')].filter((b) => b.getAttribute('aria-label') === 'Remove item 2');
  removeItem[removeItem.length - 1].click();
  assertEqual(ctx.run('FaqEditor.draft.sections[2].items.length'), 1);
});

test('editor: inline errors with the shared rules; Save shows all and sends nothing', async () => {
  const { root, field, writes } = await editor();
  type(field('sections[0].items[0].text'), '<b>bold</b>');
  assertEqual(root().querySelector('[data-error-for="sections[0].items[0].text"]').textContent,
    'Guideline must not contain < or > (no HTML).');
  type(field('sections[1].items[0].email'), 'not-an-address');
  root().querySelector('[data-faq-save]').click();
  await settle();
  assertEqual(writes().length, 0);
  assertTrue(root().querySelector('.faq-editor-alert').textContent.includes('Please correct 2 field(s).'), 'summary');
});

test('editor: preview renders the draft with the public renderer', async () => {
  const { root, field } = await editor();
  type(field('sections[0].title'), 'Rules now');
  button(root(), 'Preview').click();
  assertEqual(root().querySelector('.faq-preview .faq-section-title').textContent, 'Rules now');
  button(root(), 'Back to editing').click();
  assertTrue(root().querySelector('[data-path="sections[0].title"]'), 'editor back');
});

test('editor: save sends the normalized document with the version and shows the saved FAQ in place', async () => {
  const saved = { cs: 'ITS', version: 5, content: { sections: [DOC.sections[0]] } };
  const { root, field, writes, ctx, document } = await editor({ 'PUT /api/manage/functions/ITS/faq': { body: saved } });
  type(field('sections[0].title'), '  Rules   now ');
  root().querySelector('[data-faq-save]').click();
  await settle();
  assertEqual(writes()[0].body.version, 4);
  assertEqual(writes()[0].body.content.sections[0].title, 'Rules now');
  assertTrue(!document.querySelector('.faq-editor'), 'editor closed');
  assertTrue(ctx.run('FaqState.editing') === false && ctx.run('FaqState.view.version') === 5, 'saved view shown');
  assertTrue(document.querySelector('.ui-toast--success'), 'toast');
});

test('editor: Cancel with changes asks; a clean editor closes at once', async () => {
  const { root, field, document } = await editor();
  type(field('sections[0].title'), 'Changed');
  button(root(), 'Cancel').click();
  await settle();
  const ask = [...document.querySelectorAll('[role=dialog]')].pop();
  assertEqual(ask.querySelector('.ui-dialog-title').textContent, 'Discard your changes?');
  button(ask, 'Cancel').click();
  await settle();
  assertTrue(document.querySelector('.faq-editor'), 'still editing');
  button(root(), 'Cancel').click();
  await settle();
  button([...document.querySelectorAll('[role=dialog]')].pop(), 'Discard').click();
  await settle();
  assertTrue(!document.querySelector('.faq-editor'), 'closed');
});

test('editor: 409 keeps the draft, Reload values shows the saved version and takes its version', async () => {
  const p = await editor({ 'PUT /api/manage/functions/ITS/faq': [
    { status: 409, body: { code: 'CHANGED', message: 'Changed by someone else: reload the FAQ.' } },
    { status: 200, body: { cs: 'ITS', version: 9, content: DOC } }] });
  type(p.field('sections[0].title'), 'Mine');
  p.root().querySelector('[data-faq-save]').click();
  await settle();
  const alert = p.root().querySelector('.faq-editor-alert');
  assertTrue(alert.textContent.includes('Changed by someone else'), 'conflict message');
  p.answers['GET /api/functions/ITS/faq'] = { body: { cs: 'ITS', version: 8, content: { sections: [
    { kind: 'GUIDELINES', title: 'Theirs', items: [{ text: 'x' }] }] } } };
  button(alert, 'Reload values').click();
  await settle();
  assertEqual(p.field('sections[0].title').value, 'Mine', 'draft kept');
  assertTrue(alert.textContent.includes('Theirs'), 'saved version shown');
  p.root().querySelector('[data-faq-save]').click();
  await settle();
  assertEqual(p.writes()[1].body.version, 8, 'saved with the new version');
});

test('editor: "Edit FAQ" sits in the panel header and opens a modal dialog; the panel itself stays as it was', async () => {
  const { card, root, document } = await editor();
  assertTrue(card.querySelector('.faq-head [data-faq-edit]'), 'edit button in the header line');
  assertEqual(root().getAttribute('role'), 'dialog');
  assertEqual(root().getAttribute('aria-modal'), 'true');
  assertEqual(root().querySelector('.ui-dialog-title').textContent, 'Edit FAQ: ITS');
  assertTrue(root().querySelector('.ui-dialog-footer [data-faq-save]'), 'Save in the fixed footer');
  assertEqual(card.querySelectorAll('.faq-section-title').length, 3, 'panel not replaced by the editor');
  assertTrue([...root().querySelectorAll('button')].find((b) => b.getAttribute('aria-label') === 'Move item 1 up').disabled,
    'first item cannot move up');
  assertTrue(!document.querySelector('.faq-editor').textContent.includes(String.fromCharCode(0x2191)), 'icon buttons, no arrow glyphs');
  button(root(), 'Cancel').click();
  await settle();
  assertTrue(!document.querySelector('.faq-editor'), 'a clean editor closes at once');
  assertTrue(document.getElementById('faqCard').querySelector('[data-faq-edit]'), 'panel rendered again with its button');
});

test('editor: ESC in a field does not lose the draft', async () => {
  const { field, document } = await editor();
  type(field('sections[0].title'), 'Kept');
  key(field('sections[0].title'), 'Escape');
  assertEqual(document.querySelector('[data-path="sections[0].title"]').value, 'Kept');
});

done('faq check');
