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

test('the palette has 13 appliance, 21 furniture and 6 生活 looks, all with footprints', () => {
  assert.equal(HM.ORDER.app.length, 13);
  assert.equal(HM.ORDER.fur.length, 21);
  assert.equal(HM.ORDER.liv.length, 6);
  assert.deepEqual([...HM.ORDER.app, ...HM.ORDER.fur, ...HM.ORDER.liv].sort(), Object.keys(HM.KINDS).sort(), 'every look is in exactly one group');
  for (const k of [...HM.ORDER.app, ...HM.ORDER.fur, ...HM.ORDER.liv]) assert.ok(HM.KINDS[k].w > 0 && HM.KINDS[k].d > 0, k);
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
    // 客餐厅 is offered only while a plan has it (roomOptions adds the plan's names); 拼户型 turns it into 客厅 / 餐厅.
    for (const n of names) assert.ok(rooms.includes(n) || n === '客餐厅', `${key}: ${n}`);
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

test('1.7.0 · a dragged look turns its back to a wall it comes near; tables stay as they are', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const bb = HM.bbox(rects, '主卧'), f = HM.KINDS.fridgeA; // 主卧 is [0,0]-[3.6,3.6]
  const right = HM.clampPlacement(f, bb, 3.6 - HM.GAP - f.d / 2 - .2, 1.8, 0, true);
  assert.equal(right.rot, 3); assert.ok(Math.abs(right.x - (3.6 - HM.GAP - f.d / 2)) < 1e-9, 'backed flush');
  assert.equal(HM.clampPlacement(f, bb, 1.8, 3.3, 0, true).rot, 2);
  assert.equal(HM.clampPlacement(f, bb, 1.8, 1.8, 1, true).rot, 1, 'mid-room keeps its turn');
  assert.equal(HM.clampPlacement(f, bb, 3.2, 1.8, 0).rot, 0, 'no auto turn without a drag');
  assert.equal(HM.clampPlacement(HM.KINDS.diningB, bb, 3.0, 1.8, 0, true).rot, 0, 'tables never turn');
  const bath = HM.bbox(rects, '卫生间'), sofa = HM.KINDS.sofaA; // 1.9 m wide: sofaA (2.1 m) only fits turned sideways
  assert.equal(HM.clampPlacement(sofa, bath, (bath.x0 + bath.x1) / 2, .6, 1, true).rot, 1, 'never turns into a size that does not fit');
  const lr = HM.bbox(rects, '客餐厅'), seamX = lr.rs[0].r[2]; // the open seam between the two parts of the L
  const across = HM.clampPlacement(f, lr, seamX - .45, lr.rs[1].r[1] + 1.2, 0, true);
  assert.equal(across.rot, 0, 'the open seam of an L-shaped room is not a wall');
  assert.equal(HM.clampPlacement(f, lr, seamX - .45, lr.rs[0].r[1] + 1.2, 0, true).rot, 3, 'the kitchen wall on the same line still is');
  const corner = HM.clampPlacement(f, bb, .5, .5, 1, true);
  assert.equal(corner.rot, 1, 'in a corner the current wall wins');
});

test('1.7.3 · in a narrow room only the wall a drag heads for can turn a look; exactly 35 cm snaps', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const bb = HM.bbox(rects, '次卧'), bed = HM.KINDS.bedA, [x0, , x1] = bb.rs[0].r, cx = (x0 + x1) / 2; // 3.0 m wide: side walls always within 35 cm
  const down = { dx: 0, dz: .06 }, up = { dx: 0, dz: -.06 };
  assert.equal(HM.clampPlacement(bed, bb, cx, 1.8, 0, down).rot, 0, 'crossing the middle keeps the bed facing the room');
  assert.equal(HM.clampPlacement(bed, bb, cx, 1.8, 1, up).rot, 1, 'no wall ahead yet: keeps its turn');
  const flush = x0 + HM.GAP + bed.d / 2;
  assert.equal(HM.clampPlacement(bed, bb, flush, 1.2, 1, up).rot, 1, '1.8.4: slid straight along the side wall into the corner it keeps the side wall');
  assert.equal(HM.clampPlacement(bed, bb, cx, 1.2, 1, up).rot, 0, '1.8.4: from the middle of the room, straight up to the head wall backs onto it');
  assert.equal(HM.clampPlacement(bed, bb, cx, 1.2, 1, { dx: .04, dz: -.05 }).rot, 0, '1.8.2: dragged back towards the head wall and off the side wall it backs onto it again');
  assert.equal(HM.clampPlacement(bed, bb, x0 + .93, 1.8, 0, { dx: -.06, dz: .01 }).rot, 1, 'pushed into a side wall it turns to it');
  const f = HM.KINDS.fridgeA, mb = HM.bbox(rects, '主卧');
  assert.equal(HM.clampPlacement(f, mb, 3.6 - HM.GAP - f.d / 2 - .35, 1.8, 0, true).rot, 3, 'exactly 35 cm away snaps');
});

test('1.8.1 · the wall a look backs onto keeps it until it leaves that wall; no clear heading falls back to the nearest wall', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const bed = HM.KINDS.bedA, along = { dx: .05, dz: 0 }, back = { dx: -.05, dz: .003 };
  for (const room of ['次卧', '次卧2']) { // 3.0 m and 2.7 m: both side walls in range from the middle
    const bb = HM.bbox(rects, room), [x0, z0, x1] = bb.rs[0].r, zHead = z0 + HM.GAP + bed.d / 2;
    for (const x of [x0 + 1.2, (x0 + x1) / 2, x1 - 1.2]) for (const h of [along, back]) {
      const p = HM.clampPlacement(bed, bb, x, zHead, 0, h);
      assert.equal(p.rot, 0, `${room}: sliding along the head wall keeps the bed on it (x ${x.toFixed(2)})`);
      assert.ok(Math.abs(p.z - zHead) < 1e-9, 'still flush with the head wall');
    }
  }
  const mb = HM.bbox(rects, '主卧'), f = HM.KINDS.fridgeA, zb = HM.GAP + f.d / 2; // 主卧 [0,0]-[3.6,3.6]
  for (const k of ['fridgeA', 'sofaA', 'cabA', 'bedA']) { const d = HM.KINDS[k];
    assert.equal(HM.clampPlacement(d, mb, 3.6 - HM.GAP - d.w / 2, HM.GAP + d.d / 2, 0, { dx: .05, dz: 0 }).rot, 0, `${k}: slid along the back wall into the corner it stays on the back wall`); }
  const bal = HM.bbox(rects, '阳台'), [bx0, bz0, bx1] = bal.rs[0].r, c = HM.KINDS.cabA;
  assert.equal(HM.clampPlacement(c, bal, (bx0 + bx1) / 2 + .3, bz0 + HM.GAP + c.d / 2, 0, { dx: .05, dz: .01 }).rot, 0, 'on the balcony the far wall does not take over');
  for (const deg of [55, 65, 72, 80]) { // approaching the right wall at an angle, 10 cm off it, from a look facing the back wall but off it
    const a = deg * Math.PI / 180, h = { dx: .05 * Math.cos(a), dz: .05 * Math.sin(a) };
    assert.equal(HM.clampPlacement(f, mb, 3.6 - HM.GAP - f.d / 2 - .1, 1.8, 0, h).rot, 3, `${deg}° off square still backs onto the wall`);
  }
  assert.equal(HM.clampPlacement(f, mb, 3.6 - HM.GAP - f.d / 2 - .1, 1.8, 0, true).rot, 3, 'no heading known: nearest wall');
  assert.equal(HM.clampPlacement(f, mb, 3.6 - HM.GAP - f.d / 2 - .1, 1.8, 0, { dx: 0, dz: 0 }).rot, 0, '1.8.4: a finger back at its anchor heads for no wall');
});

// Feeds a drag step by step like home3d does: the raw target moves, the look's turn carries over, and the heading is
// measured from the last point more than 5 cm back (the previous heading standing in while the new one is under 3 cm and
// still points roughly the same way), and whether the last move left the look snapped.
function dragPath(dims, bb, x, z, rot, dx, dz, steps) {
  const out = []; let last = [x, z], hd = null, on = false;
  for (let i = 1; i <= steps; i++) {
    const tx = x + dx * i, tz = z + dz * i; let mv = { dx: tx - last[0], dz: tz - last[1] }; const len = Math.hypot(mv.dx, mv.dz), h = hd;
    if (len > .05) { last = [tx, tz]; hd = mv; } else if (len < .03 && h && (len < .01 || mv.dx * h.dx + mv.dz * h.dz > .5 * len * Math.hypot(h.dx, h.dz))) mv = h;
    const p = HM.clampPlacement(dims, bb, tx, tz, rot, { ...mv, on }); rot = p.rot; on = p.snap; out.push(p);
  }
  return out;
}

test('1.8.2 · pulling a bed off its head wall never swings it to a side wall; it can always get back', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const bed = HM.KINDS.bedA;
  for (const room of ['次卧', '次卧2', '主卧']) {
    const bb = HM.bbox(rects, room), [x0, z0, x1] = bb.rs[0].r, zHead = z0 + HM.GAP + bed.d / 2;
    const xs = []; for (let x = x0 + HM.GAP + bed.w / 2; x <= x1 - HM.GAP - bed.w / 2 + 1e-9; x += .05) xs.push(x);
    // 主卧 is wide: a bed already touching a side wall and pulled into it may back onto it; elsewhere it never turns.
    const free = x => room !== '主卧' || Math.abs(x - (x0 + x1) / 2) <= .06;
    for (const x of xs) for (const deg of free(x) ? (room === '主卧' ? [0, 20, 30, -20, -30] : [0, 20, 30, 45, -20, -30, -45]) : [0]) {
      const a = deg * Math.PI / 180, path = dragPath(bed, bb, x, zHead, 0, .05 * Math.sin(a), .05 * Math.cos(a), 12); // 60 cm
      assert.ok(path.every(p => p.rot === 0), `${room} x ${x.toFixed(2)} pulled ${deg}°: turned to ${path.find(p => p.rot)?.rot}`);
    }
  }
  const bb = HM.bbox(rects, '次卧'), [x0, z0] = bb.rs[0].r;
  const seeded = dragPath(bed, bb, 6.7, z0 + HM.GAP + bed.d / 2, 0, 0, .05, 12); // the seeded 次卧 bed, 60 cm down
  const nudge = dragPath(bed, bb, 6.7, seeded.at(-1).z, 0, -.05, 0, 2); // then 10 cm towards the near side wall
  assert.ok([...seeded, ...nudge].every(p => p.rot === 0), 'a small sideways nudge after the pull does not turn it');
  const side = HM.clampPlacement(bed, bb, x0 + .5, 2.0, 0, { dx: -.06, dz: 0 });
  assert.equal(side.rot, 1, 'pushed hard into a side wall it turns to it');
  assert.equal(dragPath(bed, bb, side.x, side.z, 1, 0, -.01, 100).at(-1).rot, 0, '1.9.1: slid straight along the side wall up to the head wall it backs onto the head wall');
  const cx = (bb.rs[0].r[0] + bb.rs[0].r[2]) / 2, tx = cx - side.x, tz = z0 + HM.GAP + bed.d / 2 - side.z, n = Math.ceil(Math.hypot(tx, tz) / .01);
  const back = dragPath(bed, bb, side.x, side.z, 1, tx / n, tz / n, n);
  assert.equal(back.at(-1).rot, 0, 'dragged back to the head wall centre it backs onto it again');
  assert.ok(Math.abs(back.at(-1).z - (z0 + HM.GAP + bed.d / 2)) < 1e-9, 'flush with the head wall');
  const across = dragPath(bed, bb, (bb.rs[0].r[0] + bb.rs[0].r[2]) / 2, 2.0, 0, .05, 0, 4);
  assert.ok(across.every(p => p.rot !== 3) || across.at(-1).rot === 3, 'no flip-flop crossing the room');
});

test('1.8.2 · a chair goes from one long balcony wall to the other and back', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const bal = HM.bbox(rects, '阳台'), [bx0, bz0, bx1, bz1] = bal.rs[0].r, ch = HM.KINDS.armA, cx = (bx0 + bx1) / 2;
  const there = dragPath(ch, bal, cx, bz0 + HM.GAP + ch.d / 2, 0, 0, .05, 10);
  assert.equal(there.at(-1).rot, 2, 'turned to the far wall'); assert.ok(Math.abs(there.at(-1).z - (bz1 - HM.GAP - ch.d / 2)) < 1e-9);
  const home = dragPath(ch, bal, cx, there.at(-1).z, 2, 0, -.05, 10);
  assert.equal(home.at(-1).rot, 0, 'and back again');
  const c = HM.KINDS.cabA;
  assert.ok(dragPath(c, bal, cx - .5, bz0 + HM.GAP + c.d / 2, 0, .05, .005, 10).every(p => p.rot === 0), 'a cabinet sliding along the wall stays on it');
});

test('1.8.3 · one footprint for every wall: no flip-flops, corners keep their wall however far the finger overshoots, beds snap in any bedroom', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const lr = HM.bbox(rects, '客餐厅'), sofa = HM.KINDS.sofaA, A = lr.rs.find(p => p.r[0] === 0).r;
  const walk = dragPath(sofa, lr, A[2] - 1.6, (A[1] + A[3]) / 2, 2, .01, 0, 140); // across 客餐厅 towards the kitchen wall
  assert.ok(walk.reduce((n, p, i) => n + (i && p.rot !== walk[i - 1].rot), 0) <= 1, 'turns at most once');
  for (const room of ['主卧', '次卧', '厨房']) for (const k of ['fridgeA', 'cabA', 'bedA']) {
    const bb = HM.bbox(rects, room), [x0, z0, x1, z1] = bb.rs[0].r, d = HM.KINDS[k], zf = z0 + HM.GAP + d.d / 2, flushX = x0 + HM.GAP + d.w / 2;
    if (d.w > x1 - x0 - 2 * HM.GAP || z1 - z0 <= d.d + 2 * HM.GAP + .7) continue; // doesn't fit, or a narrow pair (where the wall it heads for may take over)
    for (const o of [0, .16, .3]) {
      const n = Math.round((x1 - 1.2 - (flushX - o)) / .01), p = dragPath(d, bb, x1 - 1.2, zf, 0, -.01, 0, n);
      assert.equal(p.at(-1).rot, 0, `${room}/${k}: slid into the corner, finger ${o * 100} cm past flush, keeps the back wall`);
    }
  }
  const mb = HM.bbox(rects, '主卧'), bed = HM.KINDS.bedA, [mx0, mz0, mx1, mz1] = mb.rs[0].r;
  const to = (x, z) => { const dx = x - 1.8, dz = z - 1.8, n = Math.ceil(Math.hypot(dx, dz) / .01); return dragPath(bed, mb, 1.8, 1.8, 0, dx / n, dz / n, n).at(-1).rot; };
  assert.equal(to(mx0 + HM.GAP + bed.w / 2 + .15, 1.8), 1, '主卧 bed stopped 15 cm off the left wall backs onto it');
  assert.equal(to(mx1 - HM.GAP - bed.w / 2 - .15, 1.8), 3, '... the right wall');
  assert.equal(to(1.8, mz1 - HM.GAP - bed.d / 2 - .15), 2, '... the foot wall');
  const onLeft = HM.clampPlacement(bed, mb, .3, 1.8, 1, { dx: -.06, dz: 0 }), tx = 1.8 - onLeft.x, tz = 1.155 - onLeft.z, n = Math.ceil(Math.hypot(tx, tz) / .01);
  assert.equal(dragPath(bed, mb, onLeft.x, onLeft.z, 1, tx / n, tz / n, n).at(-1).rot, 0, 'a side-wall bed dragged to the head wall centre backs onto it, no extra push');
});

test('1.8.4 · sliding along a wall of a narrow pair into a corner keeps that wall; a finger held still turns nothing', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const bal = HM.bbox(rects, '阳台'), [bx0, bz0, bx1, bz1] = bal.rs[0].r;
  for (const k of ['washerA', 'fridgeA', 'armA', 'microB', 'catTree']) { const d = HM.KINDS[k], zf = bz0 + HM.GAP + d.d / 2, cx = (bx0 + bx1) / 2;
    for (const [end, label] of [[bx0 + HM.GAP + d.w / 2, 'left'], [bx1 - HM.GAP - d.w / 2, 'right']]) for (const inw of [0, .1]) {
      const n = Math.round(Math.abs(end - cx) / .01), p = dragPath(d, bal, cx, zf, 0, (end - cx) / n, inw / n, n);
      assert.ok(p.every(q => q.rot === 0), `阳台 ${k}: slid into the ${label} corner (${inw * 100} cm inward) keeps the back wall`);
    }
  }
  const ch = HM.KINDS.armA, there = dragPath(ch, bal, (bx0 + bx1) / 2, bz0 + HM.GAP + ch.d / 2, 0, 0, .05, 10);
  assert.equal(there.at(-1).rot, 2, 'a chair pushed across the balcony still turns to the far wall');
  for (const room of ['次卧', '次卧2']) { // staged: along the foot of the side wall to the middle, then straight up to the head wall
    const bb = HM.bbox(rects, room), [x0, z0, x1] = bb.rs[0].r, bed = HM.KINDS.bedA, cx = (x0 + x1) / 2;
    const side = HM.clampPlacement(bed, bb, x0 + .5, 2.0, 0, { dx: -.06, dz: 0 }); assert.equal(side.rot, 1, `${room}: pushed onto the side wall`);
    const n1 = Math.round((cx - side.x) / .01), mid = dragPath(bed, bb, side.x, side.z, 1, (cx - side.x) / n1, 0, n1);
    const zHead = z0 + HM.GAP + bed.d / 2, n2 = Math.round((side.z - zHead) / .01), up = dragPath(bed, bb, cx, side.z, mid.at(-1).rot, 0, -(side.z - zHead) / n2, n2);
    assert.equal(up.at(-1).rot, 0, `${room}: slid to the middle, then up to the head wall, it backs onto the head wall`);
  }
  const ki = HM.bbox(rects, '厨房'), [kx0, kz0, kx1] = ki.rs[0].r, bed = HM.KINDS.bedA, kz = kz0 + HM.GAP + bed.d / 2, kc = (kx0 + kx1) / 2;
  const kEnd = kx0 + HM.GAP + bed.w / 2, kn = Math.max(1, Math.round((kc - kEnd) / .01));
  // 1.9.1: the 厨房 bed lies across the room like a side-wall bed in 次卧, so slid to the end wall it backs onto it.
  assert.equal(dragPath(bed, ki, kc, kz, 0, (kEnd - kc) / kn, 0, kn).at(-1).rot, 1, '厨房 bed slid to the end wall backs onto it');
  // A finger held still with a few px of jitter: headings shorter than 3 cm head for no wall.
  const mb = HM.bbox(rects, '主卧'), f = HM.KINDS.fridgeA, x = 3.6 - HM.GAP - f.d / 2 - .3;
  for (const [dx, dz] of [[.01, 0], [.02, .01], [-.01, .02], [0, 0]]) assert.equal(HM.clampPlacement(f, mb, x, 1.8, 0, { dx, dz }).rot, 0, `jitter (${dx}, ${dz}) turns nothing`);
  assert.equal(HM.clampPlacement(f, mb, x, 1.8, 0, { dx: .04, dz: 0 }).rot, 3, 'a 4 cm push towards the wall still snaps');
});

test('1.9.0 · block plans: old templates turn into blocks with the same rooms; bathrooms sit inside a room and name themselves', () => {
  for (const k of HM.TEMPLATE_ORDER) {
    const t = HM.planOf({ template: k }), b = HM.planOf(HM.toBlocks(t));
    assert.equal(b.key, 'blocks');
    assert.deepEqual(HM.makeRects(b).rects.map(o => [o.name, o.type, o.r]), HM.makeRects(t).rects.map(o => [o.name, o.type, o.r]), `${k}: same rooms, names and walls`);
  }
  const home = { blocks: [
    { id: 'a', type: '主卧', x: 0, z: 0, w: 3.6, d: 3.6 }, { id: 'c', type: '餐厅', x: 3.6, z: 0, w: 3, d: 3.3 }, { id: 'e', type: '次卧', x: 0, z: 3.6, w: 3, d: 3 },
    { id: 'f', type: '次卧', x: 3, z: 3.6, w: 3, d: 3 }, { id: 'g', type: '次卧', x: 1, z: 1, w: 2, d: 2 }, { id: 'h', type: '车库', x: 20, z: 0, w: 3, d: 3 },
    { id: 'p', type: '阳台', x: 0, z: 6.6, w: 3, d: 1.4 }], baths: [
    { id: 'w1', host: 'a', w: 1.6, d: 2, ax: 1, az: 0 }, { id: 'w2', host: 'c', w: 1.2, d: 1.2, ax: 0, az: 0, name: '主卫' }, { id: 'w3', host: 'c', w: 1.2, d: 1.2, ax: 0, az: 0 },
    { id: 'w4', host: 'p', w: 1.2, d: 1.2, ax: 0, az: 0 }, { id: 'w5', host: 'c', w: 1.2, d: 1.2, ax: 1, az: 1 }] };
  const t = HM.planOf(home);
  assert.deepEqual(t.blocks.map(b => b.name), ['主卧', '餐厅', '次卧', '次卧2', '阳台'], 'overlapping and unknown blocks dropped; the second 次卧 counts on');
  assert.deepEqual(t.baths.map(b => [b.id, b.name]), [['w1', '主卫'], ['w2', '公卫'], ['w5', '公卫2']], 'a clashing bathroom and one on the balcony are dropped; 主卫 only inside 主卧');
  const { rects } = HM.makeRects(t), area = n => HM.area(rects, n);
  assert.ok(Math.abs(area('主卧') - (3.6 * 3.6 - 1.6 * 2)) < 1e-6 && Math.abs(area('主卫') - 3.2) < 1e-6, 'the bathroom takes its floor out of the room');
  assert.ok(Math.abs(area('餐厅') - (3 * 3.3 - 1.44 - 1.44)) < 1e-6, 'two bathrooms in one room');
  for (let i = 0; i < rects.length; i++) for (let j = i + 1; j < rects.length; j++) { const [a, b] = [rects[i].r, rects[j].r];
    assert.ok(!(Math.min(a[2], b[2]) - Math.max(a[0], b[0]) > 1e-6 && Math.min(a[3], b[3]) - Math.max(a[1], b[1]) > 1e-6), `${rects[i].name} and ${rects[j].name} do not overlap`); }
  assert.equal(rects.find(r => r.name === '主卫').type, '卫生间');
  const spot = HM.findSpot('bedA', rects, '主卧', []), p = HM.placeFromModel(spot, rects, '主卧'), [w, d] = HM.footprint(HM.KINDS.bedA, p.rot);
  assert.ok(!(p.x + w / 2 > 2 + 1e-6 && p.z - d / 2 < 2 - 1e-6), 'a bed placed in 主卧 stays out of its 主卫');
  assert.equal(HM.sanitizeHome(home).blocks.length, 5, 'saved as blocks');
  assert.equal(HM.sanitizeHome({ blocks: [] }), null, 'an empty plan falls back to the template');
  const L = HM.planOf(HM.toBlocks(HM.planOf({ template: 't3l2' })));
  assert.equal(L.blocks.filter(b => b.name === '客餐厅').length, 2, 'the two touching parts of 客餐厅 keep one name');
  const apart = HM.sanitizeBlocks({ blocks: [{ id: 'a', type: '客厅', name: '客厅', x: 0, z: 0, w: 3, d: 3 }, { id: 'b', type: '客厅', name: '客厅', x: 5, z: 0, w: 3, d: 3 }] });
  assert.deepEqual(apart.blocks.map(b => b.name), ['客厅', '客厅2'], 'two rooms apart never share a name');
  assert.equal(HM.nextName('公卫', new Set(['公卫', '公卫2'])), '公卫3');
});

test('1.9.0 · 拼户型 is wired in: the editor ships in home3d.js, 户型 opens it, renamed bathrooms carry their records', () => {
  assert.match(read('home3d.js'), /function createPlanEditor\(/);
  assert.match(read('home3d.js'), /a === 'layout'\) \{ setMode\('normal'\); editor\.open\(T\); \}/);
  assert.match(read('home3d.js').slice(read('home3d.js').indexOf('function createPlanEditor(')), /addEventListener\('touchstart'/, 'touch drives the drags');
  assert.match(read('app.js'), /saveHome:\(home,renames=\[\],move=null\)=>homeWrite\(next=>\{next\.project\.home=home;const to=new Map\(renames\);/);
  assert.match(read('app.js'), /HM\.roomNames\(planRects\(data\)\)/, 'the plan\'s rooms are offered as 所在空间');
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

test('1.8.1 · no six-look cap: chairs all show until the floor is 90% used', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' }));
  const list = Array.from({ length: 9 }, (_, i) => ({ id: i, kind: 'microA' }));
  const { shown, hidden } = HM.fitRoom(list, rects, '客餐厅');
  assert.equal(shown.length, 9); assert.equal(hidden.length, 0);
  const lr = [{ kind: 'sofaA' }, { kind: 'coffeeA' }, { kind: 'tvA' }, { kind: 'diningA' }, { kind: 'fridgeA' }, { kind: 'cabA' }, ...Array.from({ length: 6 }, () => ({ kind: 'chairA' }))];
  assert.equal(HM.fitRoom(lr, rects, '客餐厅').hidden.length, 0, 'six chairs beside six other looks all show');
  const huge = HM.fitRoom(Array.from({ length: 400 }, () => ({ kind: 'chairA' })), rects, '客餐厅');
  assert.ok(huge.hidden.length > 0 && huge.shown.length * .45 * .45 <= HM.area(rects, '客餐厅') * .9 + 1e-9, 'the floor-area check still caps an overfull room');
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

test('1.9.1 · a side-wall bed dragged up to the head wall backs onto it, whatever the sideways offset', () => {
  for (const t of HM.TEMPLATE_ORDER) { const { rects } = HM.makeRects(HM.planOf({ template: t }));
    for (const room of ['次卧', '次卧2']) { const bb = HM.bbox(rects, room); if (!bb) continue; const [x0, z0, x1] = bb.rs[0].r;
      for (const bed of [HM.KINDS.bedA, HM.KINDS.bedB]) { const zHead = z0 + HM.GAP + bed.d / 2;
        for (const [rot, xs, sg] of [[1, x0 + HM.GAP + bed.d / 2, 1], [3, x1 - HM.GAP - bed.d / 2, -1]]) for (const zs of [zHead + .6, zHead + 1.0]) for (let off = 0; off <= .9001; off += .1) {
          const ex = xs + sg * off, n = Math.round(Math.hypot(ex - xs, zs - zHead) / .01), p = dragPath(bed, bb, xs, zs, rot, (ex - xs) / n, (zHead - zs) / n, n);
          assert.equal(p.at(-1).rot, 0, `${t} ${room} bed on wall ${rot}, ${Math.round(off * 100)} cm sideways: ended on ${p.at(-1).rot}`);
        } } } }
});

test('1.9.1 · a look on a wall holds it 15 cm past the snap range, so a trembling finger does not swap walls', () => {
  const { rects } = HM.makeRects(HM.planOf({ template: 't3l2' })), mb = HM.bbox(rects, '主卧'), [, , x1] = mb.rs[0].r, f = HM.KINDS.fridgeA;
  const at = gap => x1 - HM.GAP - f.d / 2 - gap, tw = { dx: -.03, dz: .05 }; // a twitch away from the wall and along it
  const on = HM.clampPlacement(f, mb, at(.3), 1.8, 0, { dx: .06, dz: 0 }); assert.equal(on.rot, 3); assert.equal(on.snap, true);
  assert.equal(HM.clampPlacement(f, mb, at(.45), 1.8, 3, { ...tw, on: true }).snap, true, 'held at 45 cm');
  assert.equal(HM.clampPlacement(f, mb, at(.55), 1.8, 3, { ...tw, on: true }).snap, false, 'let go at 55 cm');
  assert.equal(HM.clampPlacement(f, mb, at(.45), 1.8, 3, { ...tw, on: false }).snap, false, 'not held when it was not on the wall');
});

test('1.9.1 · in a block plan, a side-wall bed in a converted 次卧 or a ➕ 次卧 (3.0 × 3.3) gets back to the head wall', () => {
  const home = HM.toBlocks(HM.planOf({ template: 't3l2' })); const [, w, d] = HM.SPACE_TYPES.find(s => s[0] === '次卧');
  home.blocks.push({ id: 'bz', type: '次卧', name: '次卧3', x: 20, z: 0, w, d });
  const { rects } = HM.makeRects(HM.planOf(home));
  for (const room of ['次卧', '次卧2', '次卧3']) { const bb = HM.bbox(rects, room), [x0, z0] = bb.rs[0].r;
    for (const bed of [HM.KINDS.bedA, HM.KINDS.bedB]) { const zh = z0 + HM.GAP + bed.d / 2, xs = x0 + HM.GAP + bed.d / 2;
      for (const off of [0, .3, .6, .9]) { const n = Math.round(Math.hypot(off, 1) / .01), p = dragPath(bed, bb, xs, zh + 1, 1, off / n, -1 / n, n);
        assert.equal(p.at(-1).rot, 0, `${room} ${Math.round(off * 100)} cm sideways`); } } }
});

test('1.9.2 · a look in a room with a bathroom never pokes into the bathroom or through a wall, and findSpot never picks a part it does not fit', () => {
  for (const [t, w, d, ax, az] of [['客厅', 4.5, 4.5, .5, .5], ['客厅', 4.5, 4.5, .5, 0], ['主卧', 3.6, 3.6, 1, 1], ['次卧', 3.0, 3.3, 0, .5], ['主卧', 3.6, 3.6, 1, .5]]) {
    const home = { key: 'blocks', blocks: [{ id: 'a', type: t, name: t, x: 0, z: 0, w, d }, { id: 'k', type: '厨房', name: '厨房', x: w, z: 0, w: 2.4, d: 3 }], baths: [{ id: 'w', host: 'a', name: '公卫', w: 1.6, d: 1.6, ax, az }] };
    const { rects } = HM.makeRects(HM.planOf(home)), bb = HM.bbox(rects, t), B = rects.find(r => r.type === '卫生间').r;
    for (const k of Object.keys(HM.KINDS)) { const dims = HM.KINDS[k]; if (!HM.fitsRoom(dims, rects, t)) continue;
      for (let i = 0; i <= 6; i++) for (let j = 0; j <= 6; j++) for (const rot of [0, 1, 2, 3]) {
        const p = HM.clampPlacement(dims, bb, w * i / 6, d * j / 6, rot), [fw, fd] = HM.footprint(dims, p.rot), a = [p.x - fw / 2, p.z - fd / 2, p.x + fw / 2, p.z + fd / 2];
        const inBath = Math.min(a[2], B[2]) - Math.max(a[0], B[0]) > .01 && Math.min(a[3], B[3]) - Math.max(a[1], B[1]) > .01;
        assert.ok(!inBath && a[0] > -.01 && a[1] > -.01 && a[2] < w + .01 && a[3] < d + .01, `${t} bath ${ax},${az} ${k} at ${i},${j} rot ${rot}`);
      }
      const s = HM.findSpot(k, rects, t, []); assert.ok(s, `${t} ${k} gets a spot`);
    }
  }
  const home = { key: 'blocks', blocks: [{ id: 'a', type: '次卧', name: '次卧', x: 0, z: 0, w: 3.0, d: 3.3 }], baths: [{ id: 'w', host: 'a', name: '公卫', w: 1.6, d: 2.0, ax: .5, az: .5 }] };
  const { rects } = HM.makeRects(HM.planOf(home));
  assert.equal(HM.fitsRoom(HM.KINDS.bedB, rects, '次卧'), false); assert.equal(HM.findSpot('bedB', rects, '次卧', []), null);
});

test('1.11.0 · a bundle keeps its pieces, each with its own room and look, and its room says 多个空间', () => {
  const src = base();
  src.items[0].parts = [
    { id: 'x', name: '空调', room: '次卧', amount: 319900, model: { kind: 'acA', x: .5, z: .5, rot: 0 } },
    { id: 'y', name: '电脑', room: '书房', amount: null, extra: 1 },
  ];
  src.items[0].model = { kind: 'sofaA', x: .5, z: .5, rot: 0 };
  const out = app.validate(src);
  assert.equal(out.items[0].room, '多个空间');
  assert.ok(!('model' in out.items[0]), 'a bundle has no look of its own');
  assert.equal(JSON.stringify(out.items[0].parts[1]), JSON.stringify({ id: 'y', name: '电脑', room: '书房', amount: null }));
  assert.equal(out.items[0].parts[0].model.kind, 'acA');
  assert.equal(JSON.stringify(app.validate(JSON.parse(JSON.stringify(out))).items[0].parts), JSON.stringify(out.items[0].parts), 'it round-trips');
  const same = base(); same.items[0].parts = [{ id: 'x', name: 'A', room: '次卧', amount: null }, { id: 'y', name: 'B', room: '次卧', amount: null }];
  assert.equal(app.validate(same).items[0].room, '次卧', 'pieces all in one room: that room');
  for (const bad of [[{ id: 'x', name: 'A', room: '次卧', amount: null }], [{ id: 'x', name: 'A', room: '多个空间', amount: null }, { id: 'y', name: 'B', room: '次卧', amount: null }], [{ id: 'x', name: 'A', room: '次卧', amount: null }, { id: 'x', name: 'B', room: '次卧', amount: null }]]) {
    const s = base(); s.items[0].parts = bad; assert.throws(() => app.validate(s));
  }
});

test('1.11.2 · a record id with ~ is renamed and its payments follow (3D piece ids are record~piece)', () => {
  const s = base(); s.items[0].id = 'a~x'; s.payments[0].itemId = 'a~x'; s.items.push({ ...s.items[1], id: 'a_x' });
  const out = app.validate(s);
  assert.equal(out.items[0].id, 'a_x_2');
  assert.equal(out.payments[0].itemId, 'a_x_2');
  assert.ok(out.items.every((i) => !i.id.includes('~')));
});

test('1.11.3 · 客餐厅 → 客厅/餐厅 keeps each look on its spot and files it under the part it stands in', () => {
  const t = HM.planOf({}, '三室两厅'), start = HM.toBlocks(t).blocks, parts = start.filter((b) => b.type === '客餐厅').sort((a, b) => b.w * b.d - a.w * a.d).map(({ id, x, z, w, d }) => ({ id, x, z, w, d }));
  const blocks = start.map((b) => b.type !== '客餐厅' ? b : { ...b, type: b.id === parts[0].id ? '客厅' : '餐厅', name: b.id === parts[0].id ? '客厅' : '餐厅' });
  const rects = HM.makeRects(t).rects, after = HM.planOf({ blocks, baths: [] }), newRects = HM.makeRects(after).rects;
  for (const m of [{ kind: 'sofaA', x: .3, z: .8, rot: 2 }, { kind: 'tvA', x: .3, z: .08, rot: 0 }, { kind: 'diningA', x: .62, z: .62, rot: 0 }, { kind: 'diningA', x: .85, z: .85, rot: 0 }]) {
    const was = HM.placeFromModel(m, rects, '客餐厅'), r = HM.relocate(parts, blocks, m), now = HM.placeFromModel(r.model, newRects, r.room);
    assert.ok(Math.hypot(was.x - now.x, was.z - now.z) < .02, `${m.kind} ${m.x}: moved ${was.x},${was.z} → ${now.x},${now.z}`);
    assert.equal(r.room, m.x === .85 ? '餐厅' : '客厅');
  }
  assert.deepEqual(HM.relocate(parts, blocks, undefined), { room: '客厅' }, 'no look: 客厅');
  assert.equal(HM.relocate(parts, blocks.filter((b) => !parts.some((p) => p.id === b.id)), null), null, 'both parts deleted: untouched');
});

test('1.11.4 · a stored refund dated before its payment still loads, and comes off when the payment arrives', () => {
  const s = base(); s.payments.push({ id: 'r', itemId: 'a', amount: 500, date: '2026-09-30', kind: 'refund', note: '' });
  assert.equal(app.validate(s).payments.length, 2, 'the stored ledger is not locked');
  assert.doesNotMatch(read('app.js'), /for\(const i of parsed\.items\)assertCashTimeline\(parsed,i\.id\);/, '1.11.5: a restore takes it too, with a notice');
  const b = { ...s.items[0], id: 'bd', room: '多个空间', parts: [{ id: 'x', name: '甲', room: '主卧', amount: null }, { id: 'y', name: '乙', room: '次卧', amount: null }] };
  const L = app.validate({ ...base(), items: [b], payments: [{ id: 'r', itemId: 'bd', amount: 5000, date: '2026-09-30', kind: 'refund', note: '' }, { id: 'p', itemId: 'bd', amount: 10000, date: '2026-10-01', kind: 'payment', note: '' }] });
  const out = app.payShares(L.items[0], L), sum = (a) => a.reduce((x, y) => x + y, 0);
  assert.equal(sum(out.cash), 5000, 'pieces add up to the net paid, ¥50');
  assert.equal(sum(out.rows.get('r')), 5000, 'the refund row carries its whole ¥50');
  assert.ok(out.cash.every((v) => v >= 0));
});

test('1.11.4 · HM.relocate scales a look into a resized part and clamps one standing outside every part', () => {
  // The 客餐厅 was 0..6 × 0..3 (p1 4 m wide, p2 2 m); 客厅 (p1) is now 2 m wide. 1 m in = a quarter of p1 → a quarter of 客厅.
  const parts = [{ id: 'p1', x: 0, z: 0, w: 4, d: 3 }, { id: 'p2', x: 4, z: 0, w: 2, d: 3 }];
  const r = HM.relocate(parts, [{ id: 'p1', name: '客厅', x: 0, z: 0, w: 2, d: 3 }, { id: 'p2', name: '餐厅', x: 2, z: 0, w: 2, d: 3 }], { kind: 'sofaA', x: 1 / 6, z: .5, rot: 0 });
  assert.equal(r.room, '客厅'); assert.equal(r.model.x, .25, 'scaled with the part, not kept at 1 m'); assert.equal(r.model.z, .5);
  // An L: the look at (3.2, 3) sits in the notch, nearest p1 (1 m below it), so it is pulled to p1's edge (z 2 of a 0..4 客厅).
  const L = [{ id: 'p1', x: 0, z: 0, w: 4, d: 2 }, { id: 'p2', x: 0, z: 2, w: 2, d: 2 }];
  const c = HM.relocate(L, [{ id: 'p1', name: '客厅', x: 0, z: 0, w: 4, d: 2 }, { id: 'p2', name: '餐厅', x: 0, z: 4, w: 2, d: 2 }, { id: 'q', name: '客厅', x: 0, z: 2, w: 4, d: 2 }], { kind: 'tvA', x: .8, z: .75, rot: 0 });
  assert.equal(c.room, '客厅'); assert.equal(c.model.x, .8); assert.equal(c.model.z, .5, 'clamped into p1, not left in the notch');
});

test('1.11.4 · the plan editor hands each old 客餐厅 record to HM.relocate, and the ledger applies the answer', () => {
  const src = read('home3d.js'), body = src.slice(src.indexOf('  function relocator(home) {'), src.indexOf('  const keyOf = '));
  const calls = []; const HMx = { relocate: (parts, blocks, model) => (calls.push([parts, blocks, model]), { room: '餐厅', model: { ...model, x: .1 } }) };
  const relocator = new Function('living', 'livingAt', 'HM', body + 'return relocator;')([['客餐厅', '客厅']], new Map([['客餐厅', ['P']]]), HMx);
  const home = { blocks: ['B'] }, move = relocator(home);
  assert.equal(typeof move, 'function');
  assert.deepEqual(move('客餐厅', { kind: 'tvA', x: .5 }), { room: '餐厅', model: { kind: 'tvA', x: .1 } });
  assert.deepEqual(calls, [[['P'], ['B'], { kind: 'tvA', x: .5 }]]);
  assert.equal(move('主卧', null), null, 'other rooms untouched');
  assert.equal((src.match(/onDone\(home, renamesOf\(home\), (true|false), relocator\(home\)\)/g) || []).length, 2, '完成 and the background save both pass it');
  const app = read('app.js'), fix = app.match(/const fix=(o=>\{const m=move&&move\(o\.room,o\.model\);[^\n]*?\});/)[1];
  const run = new Function('move', 'to', 'return ' + fix)((room, model) => room === '客餐厅' ? { room: '餐厅', model: { ...model, x: .1 } } : null, new Map([['次卧', '卧室']]));
  const a = { room: '客餐厅', model: { kind: 'tvA', x: .5 } }, b = { room: '次卧' }; run(a); run(b);
  assert.deepEqual(a, { room: '餐厅', model: { kind: 'tvA', x: .1 } }); assert.equal(b.room, '卧室');
});

test('1.11.5 · HM.relocate clamps on x too: a look in the notch beside the second part lands on its edge', () => {
  const L = [{ id: 'p1', x: 0, z: 0, w: 4, d: 2 }, { id: 'p2', x: 0, z: 2, w: 2, d: 2 }];
  // (3, 3.2): 1.2 m below p1, 1 m right of p2, so it goes to p2 and is pulled back to x 2 of a 0..4 餐厅.
  const c = HM.relocate(L, [{ id: 'p1', name: '客厅', x: 0, z: 0, w: 4, d: 2 }, { id: 'p2', name: '餐厅', x: 0, z: 4, w: 2, d: 2 }, { id: 'q', name: '餐厅', x: 2, z: 4, w: 2, d: 2 }], { kind: 'diningA', x: .75, z: .8, rot: 0 });
  assert.equal(c.room, '餐厅'); assert.equal(c.model.x, .5, 'clamped into p2 on x'); assert.equal(c.model.z, .6);
});

test('1.11.5 · 完成 hands the move to the ledger, which applies it with no bath renames and to bundle pieces too', () => {
  // home3d: saveHome passes move through to api.saveHome.
  const h3 = read('home3d.js'), fn = h3.slice(h3.indexOf('  function saveHome(home, renames, quiet, move) {'), h3.indexOf('  function', h3.indexOf('  function saveHome(home, renames, quiet, move) {') + 10));
  const got = []; const api = { saveHome: (...a) => (got.push(a), Promise.resolve(false)), toast() {} };
  const saveHome = new Function('HM', 'api', 'rebuildAll', 'setPlan', 'syncItems', 'renderTray', 'invalidate', 'let T, planKey, view;' + fn + 'return saveHome;')({ planOf: () => ({ key: 'k' }), sizesOf: () => ({}) }, api, () => {}, () => false, () => {}, () => {}, () => {});
  const mv = () => null; saveHome({}, [], true, mv);
  assert.equal(got[0][2], mv, 'move reaches api.saveHome');
  // app.js: saveHome applies move even when nothing was renamed, to plain records and to each piece of a bundle.
  const app = read('app.js'), src = app.match(/saveHome:(\(home,renames=\[\],move=null\)=>homeWrite\(next=>\{[\s\S]*?\}\}\)),\n/)[1];
  const next = { project: {}, items: [{ id: 'a', room: '客餐厅', model: { kind: 'tvA', x: .5 } }, { id: 'b', room: '多个空间', parts: [{ id: 'x', room: '客餐厅', model: { kind: 'sofaA', x: .5 } }, { id: 'y', room: '主卧' }] }] };
  const run = new Function('homeWrite', 'partsOf', 'bundleRoom', 'return ' + src)((f) => f(next), (i) => i.parts || null, (ps) => new Set(ps.map((p) => p.room)).size > 1 ? '多个空间' : ps[0].room);
  run({ k: 1 }, [], (room, model) => room === '客餐厅' ? { room: '餐厅', model: { ...model, x: .1 } } : null);
  assert.deepEqual(next.items[0], { id: 'a', room: '餐厅', model: { kind: 'tvA', x: .1 } });
  assert.deepEqual(next.items[1].parts[0], { id: 'x', room: '餐厅', model: { kind: 'sofaA', x: .1 } }, 'the piece moves');
  assert.equal(next.items[1].parts[1].room, '主卧'); assert.deepEqual(next.project.home, { k: 1 });
});

test('1.11.3 · the plan editor refuses drags past 60 rooms / 40 bathrooms and keeps a refused session as a draft', () => {
  const src = read('home3d.js');
  assert.match(src, /freeBath\(it\) && rooms\.length >= HM\.BLOCK_MAX\) toast\(`最多\$\{HM\.BLOCK_MAX\}个房间/);
  assert.match(src, /full = into && baths\.length >= HM\.BATH_MAX/);
  assert.match(src, /function leave\(\) \{ if \(el\.hidden\) return; if \(!done\(true\)\) \{ cancelDrag\(\); draft = \{ key: base/);
  assert.match(src, /startNames = names; base = keyOf\(HM\.planOf\(home\)\);/, 'a background save moves the draft key');
  assert.match(src, /label: '放弃'/, 'a restored draft can be dropped');
  assert.match(src, /keep\.add\(k \+ '\|' \+ kit\(best\)\[1\]\.name\)/, 'a kitchen boxed in by bedrooms keeps one door');
});
