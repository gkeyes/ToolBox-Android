const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const dir = path.resolve(__dirname, '..');
const read = (name) => fs.readFileSync(path.join(dir, name), 'utf8');
const HM = require('../home-model.js');

// Load app.js in a VM with an inert DOM, so validate() is tested exactly as the app runs it.
function loadApp() {
  const inert = new Proxy(function () {}, {
    get: (target, key) => key === Symbol.iterator ? function* () {} : key === Symbol.toPrimitive ? () => '' : key === 'then' ? undefined : inert,
    apply: () => inert, construct: () => inert, set: () => true,
  });
  const document = new Proxy({}, { get: (t, key) => key === 'querySelectorAll' ? () => [] : inert });
  const window = { ZZHomeModel: HM, ZZRuntime: { init: async () => {}, get: async () => null, set: async () => {}, remove: async () => {}, onFlush() {}, explain: (e) => String(e?.message || e), isNative: true }, addEventListener() {}, visualViewport: null, scrollTo() {}, innerHeight: 800 };
  const context = { window, document, location: { hash: '' }, history: { replaceState() {} }, crypto: globalThis.crypto, setTimeout: () => 0, clearTimeout() {}, requestAnimationFrame: () => 0, console, Promise, Event: class {}, FormData: class {}, TextDecoder, URL, Blob: class {} };
  window.window = window;
  vm.runInNewContext(read('app.js'), context);
  return window.ZZApp;
}
const app = loadApp();
const base = () => ({
  schemaVersion: 2, revision: 1, completedDrafts: [],
  project: { name: '我的新家', area: '98', layout: '三室两厅', budget: 30000000, initialBudget: 30000000 },
  items: [
    { id: 'a', name: '沙发', category: 'furniture', room: '客餐厅', budget: null, price: 860000, costMode: 'estimated', status: 'confirmed', merchant: '', due: '', note: '' },
    { id: 'b', name: '水电', category: 'construction', room: '全屋', budget: 100, price: null, costMode: 'estimated', status: 'estimate', merchant: '', due: '', note: '' },
  ],
  payments: [{ id: 'p', itemId: 'a', amount: 1000, date: '2026-10-01', kind: 'payment', note: '' }], changes: [],
});

test('a 1.3.0 ledger without models or a floor plan still loads unchanged', () => {
  const out = app.validate(base());
  assert.equal(out.items.length, 2);
  assert.ok(!('model' in out.items[0]));
  assert.ok(!('home' in out.project));
  const v1 = base(); v1.schemaVersion = 1; delete v1.completedDrafts; delete v1.revision;
  assert.equal(app.validate(v1).items[0].costMode, 'estimated');
});

test('validate keeps a valid look and clamps its position', () => {
  const src = base();
  src.items[0].model = { kind: 'sofaA', x: 1.7, z: -3, rot: 6, extra: 'dropped' };
  src.project.home = { template: 't2l1', sizes: { back: [9, 2, 3], D1: 3.6, D2: 4.6, L: 6, K: 4.6, BW: 3.6 }, junk: 1 };
  const out = app.validate(src);
  assert.deepEqual(out.items[0].model, { kind: 'sofaA', x: 1, z: 0, rot: 2 });
  assert.equal(out.project.home.template, 't2l1');
  assert.deepEqual(Object.keys(out.project.home).sort(), ['sizes', 'template']);
  assert.equal(out.project.home.sizes.back.length, 3);
  assert.ok(out.project.home.sizes.back[0] <= 12);
  assert.equal(app.validate(JSON.parse(JSON.stringify(out))).items[0].model.kind, 'sofaA', 'a saved ledger round-trips');
});

test('validate drops unknown looks and unknown templates without rejecting the ledger', () => {
  const src = base();
  src.items[0].model = { kind: 'spaceship', x: .5, z: .5, rot: 0 };
  src.items[1].model = 'sofaA';
  src.project.home = { template: 't9l9' };
  const out = app.validate(src);
  assert.ok(!('model' in out.items[0]));
  assert.ok(!('model' in out.items[1]));
  assert.ok(!('home' in out.project));
  assert.equal(HM.sanitizeModel({ kind: 'toString' }), null, 'prototype keys are not looks');
  assert.deepEqual(HM.sanitizeModel({ kind: 'bedA', x: 'a', z: NaN, rot: 1.5 }), { kind: 'bedA', x: .5, z: .5, rot: 0 });
});

test('the appliance and furniture palette has 13 and 12 looks, all with footprints', () => {
  assert.equal(HM.ORDER.app.length, 13);
  assert.equal(HM.ORDER.fur.length, 12);
  for (const k of [...HM.ORDER.app, ...HM.ORDER.fur]) assert.ok(HM.KINDS[k].w > 0 && HM.KINDS[k].d > 0, k);
  assert.ok(read('home3d.js').includes('hoodB:'), 'every look is drawn in home3d.js');
  for (const k of Object.keys(HM.KINDS)) assert.match(read('home3d.js'), new RegExp(`\\b${k}: \\(\\)`), k);
});

test('the floor plan follows 户型 text, else 三室两厅', () => {
  const cases = { '三室两厅': 't3l2', '三室一厅': 't3l1', '3室2厅1卫': 't3l2', '3室1厅': 't3l1', '两室一厅': 't2l1', '二室两厅': 't2l1', '2室1厅': 't2l1', '一室一厅': 't1l1', '1室0厅': 't1l1', '四室两厅': 't3l2', '': 't3l2', '大平层': 't3l2', undefined: 't3l2' };
  for (const [text, key] of Object.entries(cases)) assert.equal(HM.deriveTemplate(text === 'undefined' ? undefined : text), key, text);
  assert.equal(HM.planOf(null, '两室一厅').key, 't2l1');
  assert.equal(HM.planOf({ template: 't1l1' }, '三室两厅').key, 't1l1', 'a saved plan wins over the text');
});

test('template rooms use the ledger room names, with 次卧2 for the third bedroom', () => {
  const rooms = read('app.js').match(/const ROOMS=\[([^\]]+)\]/)[1].split(',').map((s) => s.replace(/'/g, ''));
  assert.ok(rooms.includes('次卧2'));
  for (const key of HM.TEMPLATE_ORDER) {
    const names = HM.roomNames(HM.makeRects(HM.planOf({ template: key })).rects);
    for (const n of names) assert.ok(rooms.includes(n), `${key}: ${n}`);
  }
  assert.ok(HM.roomNames(HM.makeRects(HM.planOf({ template: 't3l2' })).rects).includes('次卧2'));
});

test('placement stays inside the item\'s own room, clear of the walls', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const bb = HM.bbox(rects, '主卧'), dims = HM.KINDS.bedA;
  for (const [x, z] of [[-50, -50], [50, 50], [1, 1], [3.5, 3.5]]) {
    for (const rot of [0, 1, 2, 3]) {
      const p = HM.clampPlacement(dims, bb, x, z, rot), [w, d] = HM.footprint(dims, p.rot);
      assert.ok(p.x - w / 2 >= bb.x0 + HM.GAP - 1e-9 && p.x + w / 2 <= bb.x1 - HM.GAP + 1e-9, `x ${x},${z},${rot}`);
      assert.ok(p.z - d / 2 >= bb.z0 + HM.GAP - 1e-9 && p.z + d / 2 <= bb.z1 - HM.GAP + 1e-9, `z ${x},${z},${rot}`);
      assert.ok(p.fx >= 0 && p.fx <= 1 && p.fz >= 0 && p.fz <= 1);
    }
  }
  // An L-shaped 客餐厅: a point in the notch (the kitchen) is pulled into the nearest part of the living room.
  const lr = HM.bbox(rects, '客餐厅'), kitchen = HM.bbox(rects, '厨房').rs[0].r;
  const p = HM.clampPlacement(HM.KINDS.cabB, lr, (kitchen[0] + kitchen[2]) / 2, kitchen[1] + .3, 0);
  assert.ok(!(p.x > kitchen[0] && p.x < kitchen[2] && p.z > kitchen[1] && p.z < kitchen[3]), 'not inside the kitchen');
});

test('wall-mounted looks snap to the nearest wall and face into the room', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const bb = HM.bbox(rects, '主卧'), dims = HM.KINDS.acA; // 主卧 is [0,0]-[3.6,3.6]
  const top = HM.clampPlacement(dims, bb, 1.8, .4, 3);
  assert.equal(top.rot, 0); assert.ok(Math.abs(top.z - (HM.GAP + dims.d / 2)) < 1e-9);
  const left = HM.clampPlacement(dims, bb, .3, 1.8, 0);
  assert.equal(left.rot, 1); assert.ok(Math.abs(left.x - (HM.GAP + dims.d / 2)) < 1e-9);
  assert.equal(HM.clampPlacement(dims, bb, 1.8, 3.4, 0).rot, 2);
  assert.equal(HM.clampPlacement(dims, bb, 3.4, 1.8, 0).rot, 3);
});

test('a saved position survives a room resize as a fraction of the room', () => {
  const t = HM.planOf({ template: 't3l2' });
  const model = { kind: 'cabB', x: .5, z: .5, rot: 0 };
  const a = HM.placeFromModel(model, HM.makeRects(t).rects, '主卧');
  const wider = HM.moveHandle(t, 'b0', 4, 0);
  assert.deepEqual(wider.back.slice(0, 2), [['主卧', 4], ['卫生间', 1.5]]);
  const b = HM.placeFromModel(model, HM.makeRects(wider).rects, '主卧');
  assert.ok(Math.abs(b.x - wider.back[0][1] / 2) < 1e-9 && b.x > a.x, 'stays centred in the wider room');
  assert.equal(HM.placeFromModel(model, HM.makeRects(HM.planOf({ template: 't2l1' })).rects, '次卧2'), null, 'rooms missing from the plan show nothing');
  const t2 = HM.moveHandle(t, 'b0', 99, 0);
  assert.ok(t2.back[1][1] >= 1.4, 'neighbouring room keeps its minimum width');
  assert.equal(t2.back.reduce((s, [, w]) => s + w, 0).toFixed(2), t.back.reduce((s, [, w]) => s + w, 0).toFixed(2), 'outer walls stay put');
});

test('a full room shows at most six looks and counts the rest for +N', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const list = Array.from({ length: 9 }, (_, i) => ({ id: i, kind: 'microA' }));
  const { shown, hidden } = HM.fitRoom(list, rects, '客餐厅');
  assert.equal(shown.length, 6); assert.equal(hidden.length, 3);
  const tooBig = HM.fitRoom([{ kind: 'bedB' }], rects, '阳台');
  assert.equal(tooBig.shown.length, 0, 'a bed does not fit on the 1.4 m balcony');
  assert.equal(tooBig.hidden.length, 1);
});

test('a new look gets a free spot, and wall units go on an outer wall', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const spot = HM.findSpot('acA', rects, '次卧', []);
  assert.equal(HM.sanitizeModel(spot).kind, 'acA');
  assert.equal(spot.rot, 0, 'the 次卧 outer wall is the back wall');
  const first = HM.placeFromModel(HM.findSpot('bedA', rects, '主卧', []), rects, '主卧');
  const [w, d] = HM.footprint(HM.KINDS.bedA, first.rot);
  const second = HM.placeFromModel(HM.findSpot('cabA', rects, '主卧', [{ x: first.x, z: first.z, w, d }]), rects, '主卧');
  const [w2, d2] = HM.footprint(HM.KINDS.cabA, second.rot);
  const ox = Math.min(first.x + w / 2, second.x + w2 / 2) - Math.max(first.x - w / 2, second.x - w2 / 2);
  const oz = Math.min(first.z + d / 2, second.z + d2 / 2) - Math.max(first.z - d / 2, second.z - d2 / 2);
  assert.ok(ox <= 0.05 || oz <= 0.05, 'the wardrobe does not land on the bed');
});

test('1.4.0 ships 我的家 as a lazily imported module with vendored three.js', () => {
  const html = read('index.html');
  assert.ok(!html.includes('home3d.js') && !html.includes('three.module'), 'the 3D code is not loaded up front');
  assert.match(read('app.js'), /import\('\.\/home3d\.js'\)/);
  assert.match(read('home3d.js'), /from '\.\/vendor\/three\.module\.min\.js'/);
  for (const f of ['RoundedBoxGeometry.js', 'BufferGeometryUtils.js']) assert.ok(!/from 'three'/.test(read('vendor/' + f)), f);
  assert.match(read('vendor/LICENSE'), /MIT License/);
  assert.ok(html.indexOf('home-model.js') < html.indexOf('app.js'), 'the model loads before app.js validates');
  const entries = read('package.sh');
  for (const f of ['home-model.js', 'home3d.js', 'vendor/three.module.min.js', 'vendor/LICENSE']) assert.ok(entries.includes(f), f);
  assert.match(read('home3d.js'), /mapSize\.set\(1024, 1024\)/);
  assert.match(read('home3d.js'), /Math\.min\(window\.devicePixelRatio \|\| 1, 2\)/);
  assert.ok(!/PointLight/.test(read('home3d.js')), 'no point lights: the night glow is emissive');
  assert.match(read('home3d.js'), /addEventListener\('touchstart'/);
});

// 1.5.0 · record-time look: the form's model-kind becomes item.model when the ledger is saved.
const withFridge = () => {
  const next = app.validate(base());
  next.items.push({ id: 'n', name: '冰箱', category: 'furniture', room: '厨房', budget: null, price: null, costMode: 'actual', status: 'confirmed', merchant: '', due: '', note: '' });
  return next;
};

test('a look picked in the form is saved on the item with a free spot in its room', () => {
  const next = withFridge(), item = next.items.find((i) => i.id === 'n');
  assert.equal(app.lookOnSave(next, item, 'fridgeA'), true);
  assert.equal(item.model.kind, 'fridgeA');
  assert.deepEqual(HM.sanitizeModel(item.model), item.model, 'a saveable model');
  const out = app.validate(JSON.parse(JSON.stringify(next)));
  assert.deepEqual(out.items.find((i) => i.id === 'n').model, item.model, 'it survives validate()');
  const { rects } = HM.makeRects(HM.planOf(null, '三室两厅'));
  const ac = { id: 'ac', category: 'furniture', room: '次卧' };
  assert.equal(HM.applyLook(ac, 'acA', rects, []), true);
  assert.equal(ac.model.rot, 0, 'a wall unit goes on the outer wall');
  assert.equal(app.lookOnSave(next, { ...item, model: undefined }, ''), false, 'saving without a look still works');
  assert.equal(app.lookOnSave(next, { ...item, model: undefined }, 'spaceship'), false, 'unknown looks are ignored');
});

test('全屋, other categories and rooms missing from the plan get no look', () => {
  const next = withFridge(), item = next.items.find((i) => i.id === 'n');
  item.room = '全屋';
  assert.equal(app.lookOnSave(next, item, 'fridgeA'), false);
  assert.ok(!('model' in item));
  const water = next.items.find((i) => i.id === 'b');
  assert.equal(app.lookOnSave(next, water, 'sofaA'), false);
  assert.ok(!('model' in water));
  const { rects } = HM.makeRects(HM.planOf(null, '两室一厅'));
  assert.equal(HM.lookState('furniture', '次卧2', rects), 'missing');
  assert.equal(HM.lookState('furniture', '全屋', rects), 'whole');
  assert.equal(HM.lookState('soft', '主卧', rects), 'hidden');
  assert.equal(HM.lookState('furniture', '主卧', rects), 'ok');
});

test('re-picking a look keeps where the item already stands', () => {
  const next = withFridge(), sofa = next.items.find((i) => i.id === 'a');
  sofa.model = { kind: 'sofaA', x: .3, z: .8, rot: 2 };
  assert.equal(app.lookOnSave(next, sofa, 'sofaB'), true);
  assert.deepEqual(sofa.model, { kind: 'sofaB', x: .3, z: .8, rot: 2 });
  assert.equal(app.lookOnSave(next, sofa, 'sofaB'), false, 'the same look changes nothing');
  assert.equal(app.lookOnSave(next, sofa, ''), false, 'no new pick keeps the model');
  assert.equal(sofa.model.kind, 'sofaB');
  // A second sofa in the same room does not land on the first one.
  const other = { id: 'o', category: 'furniture', room: '客餐厅' };
  next.items.push(other);
  assert.equal(app.lookOnSave(next, other, 'sofaC'), true);
  assert.notDeepEqual([other.model.x, other.model.z], [sofa.model.x, sofa.model.z]);
});

test('the look is a named form field in both ledger forms, so drafts carry it', () => {
  const row = app.lookRow('fridgeB');
  assert.match(row, /<input type="hidden" name="model-kind" id="model-kind" value="fridgeB">/);
  assert.match(app.lookRow('<x>'), /name="model-kind" id="model-kind" value=""/, 'only known looks are written back');
  const src = read('app.js');
  const form = (id) => src.slice(src.indexOf(`<form id="${id}"`), src.indexOf('</form>', src.indexOf(`<form id="${id}"`)));
  assert.match(form('payment-form'), /\$\{lookRow\(carry\['model-kind'\]\)\}/, 'payment form');
  assert.match(form('item-form'), /\$\{lookRow\(carry\['model-kind'\]\)\}/, 'item form');
  // Outside #payment-new-fields / #payment-linked-fields, so switching target never disables it.
  const pay = form('payment-form');
  assert.ok(pay.indexOf('lookRow(') > pay.indexOf('${paymentInfo(i)}</div></div>'), 'after both target containers have closed');
  // Drafts are FormData entries (formFields), restored by field name, so a hidden named input round-trips.
  assert.match(src, /const fields=\[\.\.\.new FormData\(form\)\.entries\(\)\]/);
  assert.match(src, /form\.elements\.namedItem\(name\)/);
  assert.match(src, /lookOnSave\(next,item,f\.get\('model-kind'\)\)/);
});
