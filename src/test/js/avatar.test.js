// Photos task: the one avatar helper of every page (renderAvatar in common.js). A photo is an <img> with explicit
// width/height, loading="lazy", empty alt unless the photo alone identifies the person; no photo (or the default one)
// gives the initials on a tone that is the same for the same name; a photo that fails to load becomes the initials
// through a listener (no inline handler, no innerHTML). AD photo URLs (/api/photos/{id}?v=hash) are used as they are.
// Run: node src/test/js/avatar.test.js
const { context, readStatic, runner, assertEqual, assertTrue } = require('./support/sandbox');
const { domGlobals, Event } = require('./support/mini-dom');

const { test, done } = runner();
const PAYLOAD = '"><img src=x onerror=alert(1)>';

function page() {
  const globals = domGlobals({ fetch: () => new Promise(() => {}), Headers: class { set() {} } });
  return { globals, ctx: context(['common.js'], globals) };
}

const avatar = (ctx, person, size, options) =>
  String(ctx.run(`renderAvatar(${JSON.stringify(person)}, ${JSON.stringify(size)}, ${JSON.stringify(options || {})})`));

test('a photo: img with width/height of its size, lazy, async decoding, empty alt, decorative', () => {
  const { ctx } = page();
  const sizes = { sm: 30, md: 40, lg: 60, xl: 88 };
  for (const [size, px] of Object.entries(sizes)) {
    const html = avatar(ctx, { name: 'Kilo, Khaki', photoUrl: '/api/photos/7?v=0123abcd' }, size);
    assertTrue(html.includes(`width="${px}" height="${px}"`), `${size}: explicit size`);
    assertTrue(html.includes('loading="lazy"') && html.includes('decoding="async"'), `${size}: lazy`);
    assertTrue(html.includes('alt=""') && html.includes('aria-hidden="true"'), `${size}: decorative`);
    assertTrue(html.includes('src="/api/photos/7?v=0123abcd"'), `${size}: the URL as given`);
    assertTrue(html.includes('data-initials="KK"'), `${size}: initials kept for the fallback`);
    assertTrue(px <= 96, 'never above the natural 96 px of an AD photo');
  }
});

test('where the photo alone identifies the person, alt is the name', () => {
  const { ctx } = page();
  const html = avatar(ctx, { name: 'Kilo, Khaki', photoUrl: '/photos/kk.jpg' }, 'md', { label: true });
  assertTrue(html.includes('alt="Kilo, Khaki"') && !html.includes('aria-hidden'), 'labelled photo');
  const initials = avatar(ctx, { name: 'Kilo, Khaki' }, 'md', { label: true });
  assertTrue(initials.includes('role="img"') && initials.includes('aria-label="Kilo, Khaki"'), 'labelled initials');
});

test('no photo or the default photo: initials on a deterministic tone', () => {
  const { ctx } = page();
  const none = avatar(ctx, { name: 'Mint, Mike' }, 'md');
  const fallback = avatar(ctx, { name: 'Mint, Mike', photoUrl: '/photos/default.jpg' }, 'md');
  assertTrue(!none.includes('<img') && !fallback.includes('<img'), 'no image');
  assertTrue(none.includes('>MM<'), 'initials');
  const tone = /avatar-tone-(\d)/.exec(none)[1];
  assertEqual(/avatar-tone-(\d)/.exec(fallback)[1], tone, 'same tone for the same name');
  assertEqual(String(ctx.run("avatarTone('Mint, Mike')")), tone, 'tone function');
  const tones = new Set(['Alfa, Amber', 'Bravo, Blue', 'Charlie, Coral', 'Delta, Denim', 'Echo, Emerald', 'Golf, Gold']
    .map((n) => ctx.run(`avatarTone(${JSON.stringify(n)})`)));
  assertTrue(tones.size > 1, 'different people get different tones');
  tones.forEach((t) => assertTrue(t >= 0 && t < 6, 'tone 0..5'));
});

test('a photo that fails to load becomes the initials (listener, textContent)', () => {
  const { globals, ctx } = page();
  const { document } = globals;
  const span = document.createElement('span');
  span.className = 'avatar avatar-md avatar-tone-2';
  span.dataset.initials = 'KK';
  const img = document.createElement('img');
  img.setAttribute('alt', '');
  span.append(img);
  document.body.append(span);
  // mini-dom has no capture phase: the event bubbles to the document listener here (error does not bubble in a browser)
  img.dispatchEvent(new Event('error', { bubbles: true }));
  assertEqual(span.querySelectorAll('img').length, 0, 'image gone');
  assertEqual(span.textContent, 'KK');
  assertTrue(span.classList.contains('avatar-tone-2'), 'keeps its tone');
  assertEqual(ctx.run('avatarFallback(null)'), false, 'nothing to do without an avatar');
});

test('payloads stay text; no inline handlers and no innerHTML in the helper', () => {
  const { ctx } = page();
  const html = avatar(ctx, { name: PAYLOAD, photoUrl: `/photos/x.jpg${PAYLOAD}` }, 'md', { label: true });
  assertTrue(!html.includes('<img src=x') && html.includes('&quot;&gt;&lt;img src=x'), 'escaped');
  assertEqual((html.match(/<img/g) || []).length, 1, 'only the photo itself is an element');
  const source = readStatic('js/common.js');
  const helper = source.slice(source.indexOf('function avatarTone'), source.indexOf('/* ---------- Employee cards'));
  assertTrue(helper.includes('function renderAvatar') && helper.length > 0, 'helper found');
  assertTrue(!/\son[a-z]+=/i.test(helper), 'no inline event handler');
  assertTrue(!/innerHTML/.test(helper), 'no innerHTML');
  const fallback = source.slice(source.indexOf('function avatarFallback'), source.indexOf('/* ---------- Employee directory'));
  assertTrue(fallback.includes('textContent') && !/innerHTML/.test(fallback), 'fallback uses textContent');
  assertTrue(/addEventListener\("error"/.test(fallback), 'a listener, in the capture phase');
});

test('every page renders its photos through renderAvatar only', () => {
  for (const file of ['js/main.js', 'js/function.js', 'js/common.js']) {
    const source = readStatic(file).replace(/function renderAvatar[\s\S]*?\n}\n/, '');
    assertTrue(!/<img[^>]*photoUrl|photoUrl[^;\n]*<img/.test(source), `${file}: no own <img> for photos`);
  }
});

done('avatar check');
