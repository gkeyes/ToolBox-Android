'use strict';
// Browser checks for what the unit tests can't see (layout and computed styles), driven by touch like the phone:
//   node examples/zhuzhang/tests/browser/ui.check.cjs
// Needs Playwright with a Chromium (PLAYWRIGHT_BROWSERS_PATH or CHROMIUM_PATH). Exits 1 on any failed check.
const http = require('node:http'), fs = require('node:fs'), path = require('node:path');
const root = path.resolve(__dirname, '..', '..');
let chromium;
for (const at of ['playwright', 'playwright-core', '/opt/node22/lib/node_modules/playwright']) { try { ({ chromium } = require(at)); break; } catch (_) {} }
if (!chromium) { console.error('Playwright not found'); process.exit(2); }
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' };
const server = http.createServer((q, r) => { const f = path.join(root, decodeURIComponent(q.url.split(/[?#]/)[0]).replace(/^\/+/, '') || 'index.html');
  if (!f.startsWith(root) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { r.writeHead(404); return r.end(); }
  r.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(r); });
const W = (ms) => new Promise((r) => setTimeout(r, ms));
const ledger = (name) => ({ schemaVersion: 2, revision: 1, completedDrafts: [], changes: [],
  project: { name, area: '118', layout: '三室两厅', budget: 30000000, initialBudget: 30000000 },
  items: [{ id: 'bd', name: '电器打包', category: 'furniture', room: '多个空间', budget: null, price: 1409700, costMode: 'estimated', status: 'confirmed', merchant: '', due: '', note: '',
    parts: [{ id: 'pa', name: '空调', room: '次卧', amount: 319900 }, { id: 'pb', name: '电脑', room: '次卧2', amount: null }, { id: 'pc', name: '电视', room: '客餐厅', amount: null }] }],
  payments: [{ id: 'q1', itemId: 'bd', amount: 1409700, date: '2026-10-14', kind: 'payment', note: '' }] });
const results = [];
const check = (name, ok, got) => { results.push({ name, ok: !!ok, got }); console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok ? '' : '  ' + JSON.stringify(got))); };
(async () => {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}/index.html`;
  const b = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  const open = async (vw, data, hash = '#ledger') => {
    const ctx = await b.newContext({ viewport: { width: vw, height: 800 }, hasTouch: true, isMobile: true });
    await ctx.addInitScript((s) => { delete window.PointerEvent; const st = new Map([['ledger.v1', s]]); window.ToolBox = { ready: async () => ({}), storage: { get: async (k) => st.has(k) ? JSON.parse(JSON.stringify(st.get(k))) : null, set: async (k, v) => { st.set(k, v); }, remove: async (k) => st.delete(k) }, files: {} }; }, data);
    const p = await ctx.newPage(); await p.goto(base + hash); await W(1200);
    const c = await ctx.newCDPSession(p);
    const tap = async (sel, text) => { for (const e of await p.$$(sel)) { if (!(await e.isVisible())) continue; if (text && !(await e.innerText()).includes(text)) continue;
      await e.evaluate((x) => x.scrollIntoView({ block: 'center', behavior: 'instant' })); await W(120); const bx = await e.boundingBox();
      await c.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: bx.x + bx.width / 2, y: bx.y + bx.height / 2 }] }); await c.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); await W(400); return; }
      throw new Error('no ' + sel + ' ' + (text || '')); };
    // Text size as Android's text zoom applies it: every font size scaled, then a resize.
    const zoom = async (z, scope = 'body') => { await p.evaluate(([z, scope]) => { const els = [...document.querySelectorAll(scope + ' *')]; for (const e of els) e.dataset.f0 ??= getComputedStyle(e).fontSize; for (const e of els) e.style.fontSize = parseFloat(e.dataset.f0) * z + 'px'; window.dispatchEvent(new Event('resize')); }, [z, scope]); await W(300); };
    return { ctx, p, tap, zoom };
  };
  // 1. Top bar: the name keeps its end, nothing passes the edge, the name clears the top.
  for (const [name, z, end] of [['滨江花园三期12栋1202', 2, '12栋1202'], ['阳光城·翡丽湾二期 B区 7栋2单元 1803室', 1.3, '7栋2单元 1803室'], ['阳光城·翡丽湾二期 B区 7栋2单元 1803室', 2, '7栋2单元 1803室'], ['我的新家', 2, '我的新家']]) {
    const { ctx, p, zoom } = await open(320, ledger(name)); await zoom(z);
    const m = await p.evaluate(() => { const n = document.querySelector('.mobile-project .project-name'), bar = document.querySelector('.topbar'), badge = document.querySelector('#runtime-badge');
      return { text: n.textContent, top: n.getBoundingClientRect().top - bar.getBoundingClientRect().top, sw: document.documentElement.scrollWidth, badgeLines: Math.round(badge.getBoundingClientRect().height / parseFloat(getComputedStyle(badge).lineHeight || 0) || 1), meta: [...document.querySelectorAll('.mobile-project .project-meta span')].map((s) => s.getBoundingClientRect().width > 0) }; });
    check(`top bar ${name} ×${z}: keeps 「${end}」`, m.text.endsWith(end), m);
    check(`top bar ${name} ×${z}: page stays 320 wide, name clears the top, every meta piece shown`, m.sw === 320 && m.top >= 6 && m.meta.every(Boolean), m);
    await ctx.close();
  }
  // 2. Refund sheet: the over-cap note turns red while typing; at ×2 the date box holds its date under its label.
  { const { ctx, p, tap, zoom } = await open(320, ledger('我的新家'));
    await tap('#nav-plus'); await tap('[data-action="payment-kind"][data-kind="refund"]');
    await tap('[data-action="form-picker"][data-field="payment-item"]'); await tap('#picker-list .pick-option', '电器打包');
    await tap('.rp-opt', '空调'); await p.focus('#payment-amount'); await p.keyboard.type('4000'); await W(200);
    const note = await p.evaluate(() => { const n = document.querySelector('.rp-note'); return n && { text: n.innerText, cls: n.className, color: getComputedStyle(n).color }; });
    check('refund over the piece\'s cash: the note says so at once', note && note.cls.includes('rp-over') && note.text.includes('退款不能超过这个数'), note);
    await zoom(2, '#sheet');
    const d = await p.evaluate(() => { const r = (s) => document.querySelector(s).getBoundingClientRect(); const cap = r('#date-caption'), btn = r('.date-button'), lab = r('#date-label'); return { capBottom: cap.bottom, btnTop: btn.top, btnBottom: btn.bottom, labTop: lab.top, labBottom: lab.bottom }; });
    check('date at ×2: the date stays inside its box, below its label', d.capBottom <= d.btnTop && d.labTop >= d.btnTop && d.labBottom <= d.btnBottom, d);
    await ctx.close(); }
  // 3. Restore dialog: the early-refund hint is really visible.
  { const { ctx, p } = await open(390, ledger('我的新家'));
    const B = ledger('我的新家'); B.payments.unshift({ id: 'r0', itemId: 'bd', amount: 1000, date: '2026-10-01', kind: 'refund', note: '' });
    await p.evaluate((t) => window.ZZApp.previewImport(t), JSON.stringify(B)); await W(500);
    const h = await p.evaluate(() => { const e = [...document.querySelectorAll('#sheet-body p')].find((x) => x.textContent.includes('退款日期早于付款')); if (!e) return null;
      const s = getComputedStyle(e), r = e.getBoundingClientRect(), hit = document.elementFromPoint(r.left + 4, r.top + r.height / 2);
      return { text: e.textContent, w: r.width, h: r.height, opacity: s.opacity, visibility: s.visibility, color: s.color, size: s.fontSize, onTop: e.contains(hit) }; });
    check('restore dialog: the early-refund hint is visible', h && h.w > 100 && h.h > 8 && +h.opacity === 1 && h.visibility === 'visible' && parseFloat(h.size) >= 9 && h.onTop, h);
    await ctx.close(); }
  await b.close(); server.close();
  const failed = results.filter((r) => !r.ok).length; console.log(`${results.length - failed}/${results.length} passed`); process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); server.close(); process.exit(1); });
