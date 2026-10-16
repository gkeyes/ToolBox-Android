'use strict';
// Random bundles built one entry at a time under the rules 记一笔 enforces (a refund no larger than the item's net, an
// aimed refund no larger than its piece's cash, the cash timeline never negative), checked after every entry:
// - every row is non-negative and sums to its own amount;
// - each piece's rows (payments minus refunds) sum to that piece's cash, and no cash is negative;
// - every aimed refund sits on its own piece only.
// Used by home.test.cjs (small, fixed seeds) and from the command line for long runs:
//   node examples/zhuzhang/tests/ledger-fuzz.cjs [ledgers=20000] [seed=1] [backdate=1]
function run(app, { n = 20000, seed = 1, backdate = true } = {}) {
  let s = seed >>> 0 || 1;
  const R = () => (s = (Math.imul(s, 1103515245) + 12345) >>> 0) / 4294967296, ri = (k) => Math.floor(R() * k);
  const st = { ledgers: n, entries: 0, made: { payment: 0, whole: 0, aimed: 0 }, rowSign: 0, rowSum: 0, cashSum: 0, negCash: 0, leak: 0, first: null };
  const fail = (key, L) => { st[key]++; if (!st.first) st.first = { key, ledger: JSON.parse(JSON.stringify(L)) }; };
  for (let t = 0; t < n; t++) {
    const np = 2 + ri(3), yuan = R() < .5, actual = R() < .4;
    const parts = [...Array(np)].map((_, k) => ({ id: 'p' + k, name: '件' + k, room: '房' + k, amount: R() < .4 ? (yuan ? 100 * (1 + ri(5000)) : 1 + ri(500000)) : null }));
    const item = { id: 'b', name: '包', category: 'furniture', room: '多个空间', budget: null, price: actual ? null : (yuan ? 100 * (1 + ri(20000)) : 1 + ri(2000000)), costMode: actual ? 'actual' : 'estimated', status: 'confirmed', merchant: '', due: '', note: '', parts };
    const L = { schemaVersion: 2, revision: 1, completedDrafts: [], project: { name: 'x', area: '', layout: '', budget: 0, initialBudget: 0 }, items: [item], payments: [], changes: [] };
    let day = 1;
    for (let e = 0, steps = 3 + ri(8); e < steps; e++) {
      day = backdate ? 1 + ri(28) : Math.min(28, day + (R() < .5 ? ri(3) : 0));
      const date = '2026-09-' + String(day).padStart(2, '0'), V = app.validate(JSON.parse(JSON.stringify(L))), out = app.payShares(V.items[0], V);
      const net = out.cash.reduce((a, b) => a + b, 0), amt = (m) => yuan && m >= 100 ? 100 * (1 + ri(Math.floor(m / 100))) : 1 + ri(m), r = R();
      let p;
      if (r < .45 || net <= 0) p = { id: 'e' + e, itemId: 'b', amount: yuan ? 100 * (1 + ri(5000)) : 1 + ri(500000), date, kind: 'payment', note: '' };
      else if (r < .7) p = { id: 'e' + e, itemId: 'b', amount: amt(net), date, kind: 'refund', note: '' };
      else { const k = ri(np); if (out.cash[k] <= 0) continue; p = { id: 'e' + e, itemId: 'b', amount: amt(out.cash[k]), date, kind: 'refund', note: '', part: 'p' + k }; }
      L.payments.push(p);
      let W;
      try { W = app.validate(JSON.parse(JSON.stringify(L))); app.assertCashTimeline(W, 'b'); } catch (_) { L.payments.pop(); continue; }
      st.entries++; st.made[p.part ? 'aimed' : p.kind === 'refund' ? 'whole' : 'payment']++;
      const o = app.payShares(W.items[0], W), tot = o.cash.map(() => 0);
      for (const q of W.payments) {
        const row = o.rows.get(q.id);
        if (row.some((v) => v < 0)) fail('rowSign', L);
        if (row.reduce((a, b) => a + b, 0) !== q.amount) fail('rowSum', L);
        row.forEach((v, j) => { tot[j] += q.kind === 'refund' ? -v : v; });
        if (q.part) { const k = +q.part.slice(1); if (row.some((v, j) => j !== k && v !== 0)) fail('leak', L); }
      }
      if (tot.some((v, j) => v !== o.cash[j])) fail('cashSum', L);
      if (o.cash.some((v) => v < 0)) fail('negCash', L);
    }
  }
  return st;
}
module.exports = { run };
if (require.main === module) {
  const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), dir = path.resolve(__dirname, '..');
  const inert = new Proxy(function () {}, { get: (t, k) => k === Symbol.iterator ? function* () {} : k === Symbol.toPrimitive ? () => '' : k === 'then' ? undefined : inert, apply: () => inert, construct: () => inert, set: () => true });
  const window = { ZZHomeModel: require('../home-model.js'), ZZRuntime: { init: async () => {}, get: async () => null, set: async () => {}, remove: async () => {}, onFlush() {}, onState() {}, explain: (e) => String(e?.message || e), isNative: true }, addEventListener() {}, visualViewport: null, scrollTo() {}, innerHeight: 800 };
  window.window = window;
  const quiet = { log() {}, warn() {}, error() {} };
  vm.runInNewContext(fs.readFileSync(path.join(dir, 'app.js'), 'utf8'), { window, document: new Proxy({}, { get: (t, k) => k === 'querySelectorAll' ? () => [] : inert }), location: { hash: '' }, history: { replaceState() {} }, crypto: globalThis.crypto, setTimeout: () => 0, clearTimeout() {}, requestAnimationFrame: () => 0, console: quiet, Promise, Event: class {}, FormData: class {}, TextDecoder, URL, Blob: class {} });
  const [n = '20000', seed = '1', backdate = '1'] = process.argv.slice(2), st = run(window.ZZApp, { n: +n, seed: +seed, backdate: backdate !== '0' });
  console.log(JSON.stringify({ ...st, first: st.first && st.first.key }));
  if (st.first) console.log(JSON.stringify(st.first.ledger));
}
