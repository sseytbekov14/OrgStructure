// Placing the chosen photo in the circle of the Change photo dialog: the start is centered and covers the whole square,
// no gap can appear however far it is dragged or zoomed, zooming keeps the middle, the square sent has no more pixels
// than the part of the photo in the circle; the canvas needs no image URL, so the CSP stays as it is.
// Run: node src/test/js/photo-crop.test.js
const { context, readStatic, runner, assertEqual, assertTrue } = require('./support/sandbox');
const { domGlobals } = require('./support/mini-dom');

const { test, done } = runner();
const SIZE = 200;
const WIDE = { width: 800, height: 400 };
const TALL = { width: 300, height: 900 };

function page() {
  const globals = domGlobals({ fetch: () => new Promise(() => {}), Headers: class { set() {} } });
  return context(['ui.js', 'common.js', 'photo-upload.js'], globals);
}

const call = (ctx, code) => JSON.parse(ctx.run(`JSON.stringify(${code})`));
const near = (actual, expected, label) => assertTrue(Math.abs(actual - expected) < 1e-6, `${label}: ${actual} != ${expected}`);

test('the start: the short side fills the circle, the photo is in the middle', () => {
  const ctx = page();
  const wide = call(ctx, `photoCropCentered(${JSON.stringify(WIDE)}, ${SIZE})`);
  assertEqual(wide, { zoom: 1, left: -100, top: 0 });
  const tall = call(ctx, `photoCropCentered(${JSON.stringify(TALL)}, ${SIZE})`);
  assertEqual(tall, { zoom: 1, left: 0, top: -200 });
});

test('dragging can never open a gap', () => {
  const ctx = page();
  const clamp = (crop) => call(ctx, `photoCropClamp(${JSON.stringify(WIDE)}, ${JSON.stringify(crop)}, ${SIZE})`);
  assertEqual(clamp({ zoom: 1, left: 50, top: 50 }), { zoom: 1, left: 0, top: 0 }, 'too far right and down');
  assertEqual(clamp({ zoom: 1, left: -900, top: -900 }), { zoom: 1, left: -200, top: 0 }, 'too far left and up');
  assertEqual(clamp({ zoom: 1, left: -60, top: 0 }), { zoom: 1, left: -60, top: 0 }, 'inside: as dragged');
  // At zoom 2 the short side also has room
  assertEqual(clamp({ zoom: 2, left: -900, top: -900 }), { zoom: 2, left: -600, top: -200 });
});

test('zooming keeps the middle of the circle and stays between 1 and the maximum', () => {
  const ctx = page();
  const start = call(ctx, `photoCropCentered(${JSON.stringify(WIDE)}, ${SIZE})`);
  const zoomed = call(ctx, `photoCropZoomed(${JSON.stringify(WIDE)}, ${JSON.stringify(start)}, 2, ${SIZE})`);
  assertEqual(zoomed.zoom, 2);
  near(zoomed.left, -300, 'left');
  near(zoomed.top, -100, 'top');
  assertEqual(call(ctx, `photoCropZoomed(${JSON.stringify(WIDE)}, ${JSON.stringify(start)}, 99, ${SIZE})`).zoom, 4, 'maximum');
  assertEqual(call(ctx, `photoCropZoomed(${JSON.stringify(WIDE)}, ${JSON.stringify(zoomed)}, 0.2, ${SIZE})`).zoom, 1, 'minimum');
  const back = call(ctx, `photoCropZoomed(${JSON.stringify(WIDE)}, ${JSON.stringify(zoomed)}, 1, ${SIZE})`);
  near(back.left, -100, 'back left');
  near(back.top, 0, 'back top');
});

test('the square sent: at most 640 px, and never more pixels than the photo has in the circle', () => {
  const ctx = page();
  const side = (image, zoom) => ctx.run(`photoCropSide(${JSON.stringify(image)}, { zoom: ${zoom} }, ${SIZE})`);
  assertEqual(side({ width: 4000, height: 3000 }, 1), 640, 'a large photo');
  assertEqual(side({ width: 4000, height: 3000 }, 4), 640, 'zoomed, still enough pixels');
  assertEqual(side({ width: 300, height: 200 }, 1), 200, 'a small photo is not blown up');
  assertEqual(side({ width: 300, height: 200 }, 2), 100, 'zoomed in: only that part');
});

test('the crop is drawn on white, in canvas pixels', () => {
  const ctx = page();
  const calls = [];
  ctx.sandbox.canvas = { width: 400, height: 400, getContext: () => ({
    set fillStyle(value) { calls.push(['fillStyle', value]); },
    set imageSmoothingQuality(value) { calls.push(['quality', value]); },
    fillRect: (...args) => calls.push(['fillRect', ...args]),
    drawImage: (image, ...args) => calls.push(['drawImage', ...args])
  }) };
  ctx.run(`photoCropDraw(canvas, ${JSON.stringify(WIDE)}, { zoom: 1, left: -100, top: 0 }, ${SIZE})`);
  assertEqual(calls[0], ['fillStyle', '#ffffff']);
  assertEqual(calls[1], ['fillRect', 0, 0, 400, 400]);
  assertEqual(calls[3], ['drawImage', -200, 0, 800, 400]);
});

test('DOM APIs only, and no image URL the Content-Security-Policy would refuse', () => {
  const source = readStatic('js/photo-upload.js');
  assertTrue(!/innerHTML/.test(source), 'no innerHTML');
  assertTrue(!/createObjectURL|toDataURL/.test(source), 'the photo is drawn on a canvas, no blob: or data: URL');
  assertTrue(/setPointerCapture/.test(source) && /addEventListener\("wheel"/.test(source), 'drag and wheel');
  assertTrue(/ArrowLeft/.test(source), 'keyboard');
});

done('photo crop check');
