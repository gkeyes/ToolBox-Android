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
    await ctx.addInitScript(() => {
      // What a person would see of an element: on screen, not faded or clipped by any ancestor, readable against what is behind it.
      const rgba = (c) => { const m = c.match(/[\d.]+/g) || []; return { r: +m[0] || 0, g: +m[1] || 0, b: +m[2] || 0, a: m[3] === undefined ? 1 : +m[3] }; };
      const lum = ({ r, g, b }) => { const f = (v) => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; }; return .2126 * f(r) + .7152 * f(g) + .0722 * f(b); };
      window.__seen = (el) => {
        const out = []; if (!el) return ['missing'];
        const r = el.getBoundingClientRect(); if (r.width < 2 || r.height < 2) out.push('no size');
        let op = 1, bg = null;
        for (let e = el; e && e.nodeType === 1; e = e.parentElement) { const s = getComputedStyle(e); op *= +s.opacity;
          if (s.display === 'none') out.push('display none'); if (s.visibility !== 'visible') out.push('visibility ' + s.visibility);
          if (!bg && rgba(s.backgroundColor).a > .5) bg = rgba(s.backgroundColor);
          if (e !== el && s.overflow !== 'visible') { const q = e.getBoundingClientRect(); if (r.left < q.left - 1 || r.right > q.right + 1 || r.top < q.top - 1 || r.bottom > q.bottom + 1) out.push('clipped by ' + (e.className || e.tagName)); } }
        if (op < .99) out.push('faded ' + op.toFixed(2));
        const s = getComputedStyle(el), c = rgba(s.color); if (c.a < .9) out.push('text alpha ' + c.a);
        bg = bg || { r: 255, g: 255, b: 255 }; const L1 = lum(c), L2 = lum(bg), ratio = (Math.max(L1, L2) + .05) / (Math.min(L1, L2) + .05); if (ratio < 3) out.push('contrast ' + ratio.toFixed(2));
        const hit = document.elementFromPoint(Math.min(innerWidth - 1, r.left + Math.min(6, r.width / 2)), r.top + r.height / 2); if (!hit || !el.contains(hit)) out.push('covered by ' + (hit && (hit.className || hit.tagName)));
        return out; };
    });
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
  for (const [name, z, end, start, width = 320] of [['滨江花园三期12栋1202', 2, '12栋1202', '滨江'], ['阳光城·翡丽湾二期 B区 7栋2单元 1803室', 1, '7栋2单元 1803室', '阳光城·翡丽湾二期'],
    ['阳光城·翡丽湾二期 B区 7栋2单元 1803室', 1.3, '7栋2单元 1803室', '阳光城'], ['阳光城·翡丽湾二期 B区 7栋2单元 1803室', 2, '7栋2单元 1803室', '阳光城'],
    ['Riverside Garden Phase 3 Bldg 12-1202', 1, '12-1202', 'Riverside Garden'], ['Riverside Garden Phase 3 Bldg 12-1202', 2, '12-1202', 'Riverside…'],
    ['北京市朝阳区望京街道花家地西里三区 2号楼 1单元 501', 1, '1单元 501', '北京市朝阳区'], ['我的新家', 2, '我的新家', '我的新家'],
    // Same estate, different buildings: at ×2 every one keeps its own building number.
    ...[6, 8, 9, 10, 11, 18].map((b) => [`阳光城·翡丽湾二期 B区 ${b}栋2单元 1803室`, 2, `${b}栋2单元 1803室`, '阳']),
    // A two-character estate on a wider phone: the 40% start may not cost the building either.
    ...[320, 390].flatMap((w) => [18, 19].flatMap((b) => [[`中海·国际社区 三期 ${b}幢 2单元 1803`, 2, `${b}幢 2单元 1803`, '中海', w], [`龙湖·春江郦城 ${b - 6}座 2单元 1803室`, 2, `${b - 6}座 2单元 1803室`, '龙湖', w]])),
    ['Sunshine Tower 18 Unit 1203 Phase 2 East', 2, 'Phase 2 East', 'Sunshine', 390],
    ...[320, 390].flatMap((w) => [12, 13].flatMap((b) => [[`上海市浦东新区张江镇碧云路 1088弄 ${b}号 1201`, 2, `${b}号 1201`, '上海', w], [`Vanke Park Phase 2 Bldg ${b}-1202`, 2, `${b}-1202`, 'Vanke', w]]))]) {
    const { ctx, p, zoom } = await open(width, ledger(name)); await zoom(z);
    const m = await p.evaluate(() => { const n = document.querySelector('.mobile-project .project-name'), bar = document.querySelector('.topbar'), small = document.querySelector('.mobile-project .project-meta');
      return { text: n.textContent, split: [...n.querySelectorAll('.nb')].filter((x) => x.getClientRects().length > 1).map((x) => x.textContent), size: parseFloat(getComputedStyle(n).fontSize), fits: n.scrollHeight <= n.clientHeight + 1, name: __seen(n), top: n.getBoundingClientRect().top - bar.getBoundingClientRect().top, sw: document.documentElement.scrollWidth,
        meta: [...small.querySelectorAll('span')].map((x) => ({ t: x.textContent, seen: __seen(x) })) }; });
    check(`top bar ${name} ${width} ×${z}: keeps 「${start}」 and 「${end}」, and what shows fits its box`, m.text.startsWith(start) && m.text.endsWith(end) && m.fits && !m.name.length && m.size >= 14 * z, m);
    // Neither side of … stops inside a word, a number, or between a number and its 栋/单元/号楼/室.
    const [hd, tl = ''] = m.text.split('…'), at = name.length - tl.length, hEnd = hd.length, glued = (a, b) => /[A-Za-z0-9]/.test(a || '') && /[A-Za-z0-9]/.test(b || '') || /[A-Za-z0-9]/.test(a || '') && /[栋幢座号楼单元室层户弄区期院]/.test(b || '');
    check(`top bar ${name} ${width} ×${z}: no line ends inside a number and its 栋/单元/弄 or a Latin word`, !m.split.length, m.split);
    check(`top bar ${name} ${width} ×${z}: cut only between words`, !m.text.includes('…') || (name.endsWith(tl) && !glued(name[at - 1], name[at]) && name.startsWith(hd) && !glued(name[hEnd - 1], name[hEnd])), { text: m.text });
    check(`top bar ${name} ${width} ×${z}: page stays ${width} wide, name clears the top, every meta piece readable in full`, m.sw === width && m.top >= 6 && m.meta.length === 2 && m.meta.every((x) => !x.seen.length), m);
    await ctx.close();
  }
  // 2. Refund sheet: the over-cap note turns red while typing; at ×2 the date box holds its date under its label.
  { const { ctx, p, tap, zoom } = await open(320, ledger('我的新家'));
    await tap('#nav-plus'); await tap('[data-action="payment-kind"][data-kind="refund"]');
    await tap('[data-action="form-picker"][data-field="payment-item"]'); await tap('#picker-list .pick-option', '电器打包');
    await tap('.rp-opt', '空调'); await p.focus('#payment-amount'); await p.keyboard.type('4000'); await W(200);
    const note = await p.evaluate(() => { const n = document.querySelector('.rp-note'); if (!n) return null; n.scrollIntoView({ block: 'center', behavior: 'instant' }); const m = getComputedStyle(n).color.match(/\d+/g).map(Number);
      return { text: n.innerText, cls: n.className, rgb: m, seen: __seen(n) }; });
    check('refund over the piece\'s cash: the note says so at once, in red, readable', note && note.text.includes('退款不能超过这个数') && note.rgb[0] >= note.rgb[1] + 60 && note.rgb[0] >= note.rgb[2] + 60 && !note.seen.length, note);
    await zoom(2, '#sheet');
    const d = await p.evaluate(() => { const r = (s) => document.querySelector(s).getBoundingClientRect(); document.querySelector('.date-button').scrollIntoView({ block: 'center', behavior: 'instant' }); const cap = r('#date-caption'), btn = r('.date-button'), lab = r('#date-label'); return { capBottom: cap.bottom, btnTop: btn.top, btnBottom: btn.bottom, labTop: lab.top, labBottom: lab.bottom, seen: __seen(document.querySelector('#date-label')) }; });
    check('date at ×2: the date stays inside its box, below its label, readable', d.capBottom <= d.btnTop && d.labTop >= d.btnTop && d.labBottom <= d.btnBottom && !d.seen.length, d);
    await ctx.close(); }
  // 3. Restore dialog: the early-refund hint is really visible.
  { const { ctx, p } = await open(390, ledger('我的新家'));
    const B = ledger('我的新家'); B.payments.unshift({ id: 'r0', itemId: 'bd', amount: 1000, date: '2026-10-01', kind: 'refund', note: '' });
    await p.evaluate((t) => window.ZZApp.previewImport(t), JSON.stringify(B)); await W(500);
    const h = await p.evaluate(() => { const e = [...document.querySelectorAll('#sheet-body p')].find((x) => x.textContent.includes('退款日期早于付款')); if (!e) return null;
      e.scrollIntoView({ block: 'center', behavior: 'instant' }); return { text: e.textContent, size: parseFloat(getComputedStyle(e).fontSize), seen: __seen(e) }; });
    check('restore dialog: the early-refund hint is readable', h && h.size >= 9 && !h.seen.length, h);
    await ctx.close(); }
  await b.close(); server.close();
  const failed = results.filter((r) => !r.ok).length; console.log(`${results.length - failed}/${results.length} passed`); process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); server.close(); process.exit(1); });
