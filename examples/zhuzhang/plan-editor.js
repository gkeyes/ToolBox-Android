// 1.9.0 · 拼户型: the floor plan as blocks seen from above. Rooms are rectangles dragged edge to edge (they snap
// within 30 cm and may not overlap); bathrooms are dashed boxes inside a room, at one of its corners, edge middles
// or centre. ➕ 加空间 adds any type, as many as wanted; 拿掉 removes one. Nothing is saved until 完成 (or Back).
// Touch events drive every drag, with mouse fallbacks, because some WebViews send no pointer events.
const HM = window.ZZHomeModel;
const SNAP = .3, GRID = .1;
const r1 = v => Math.round(v / GRID) * GRID, r2 = v => Math.round(v * 100) / 100;
const fmtA = v => v.toFixed(1) + '㎡';

export function createPlanEditor({ root, layer, svg, icons, esc, toast, openSheet, closeSheet, onTemplates, onDone }) {
  const el = document.createElement('div'); el.className = 'pe'; el.hidden = true;
  el.innerHTML = `<div class="pe-top"><div class="hv-title"><h1>拼户型</h1><p class="pe-sum"></p></div><div class="hv-tools"><button type="button" class="hg gbtn gchip" data-pe="tpl">${svg(icons.layout)}<span>范本</span></button><button type="button" class="hg gbtn gchip on" data-pe="done">${svg(icons.check)}<span>完成</span></button></div></div>
  <div class="pe-plan" role="application" aria-label="户型平面，拖动方块拼房间"></div>
  <div class="pe-dock"><div class="hg hv-selbar pe-selbar" hidden><div class="hv-who"><b class="pe-sel-n"></b><span class="pe-sel-s"></span></div><button type="button" class="hg gbtn gchip pe-del" data-pe="del">${svg(icons.trash)}<span>拿掉</span></button></div>
  <div class="pe-addrow"><button type="button" class="hg gbtn gchip pe-add" data-pe="add">${svg(icons.plus)}<span>加空间</span></button></div></div>`;
  root.appendChild(el);
  const sheet = document.createElement('div'); sheet.className = 'hv-sheet hg'; sheet.dataset.sheet = 'space';
  sheet.setAttribute('role', 'dialog'); sheet.setAttribute('aria-modal', 'true'); sheet.setAttribute('aria-label', '加空间');
  sheet.innerHTML = `<div class="hv-grab"></div><div class="hv-sheet-head"><h2>加一个什么空间？</h2><button type="button" class="hg gbtn gcirc" data-hv="close" aria-label="关闭">${svg(icons.close)}</button></div><div class="hv-sheet-body"><p class="hv-hint pe-sheet-hint"></p><div class="pe-types">${HM.SPACE_TYPES.map(([t, w, d]) => `<button type="button" class="hg gbtn${t === '卫生间' ? ' pe-bathbtn' : ''}" data-space="${t}">${t}<small>${w} × ${d} 米</small></button>`).join('')}</div></div>`;
  layer.appendChild(sheet);
  const $ = s => el.querySelector(s), plan = $('.pe-plan');

  let rooms = [], baths = [], sel = null, startNames = new Map(), uid = 0, S = 30, OX = 0, OZ = 0, drag = null;
  const byId = id => rooms.find(r => r.id === id) || baths.find(b => b.id === id);
  const host = b => rooms.find(r => r.id === b.host);
  const used = skip => new Set([...rooms, ...baths].filter(o => o !== skip).map(o => o.name));
  const areaOf = r => r.w * r.d - baths.filter(b => b.host === r.id).reduce((s, b) => s + b.w * b.d, 0);
  const bathPos = (b, h = host(b), ax = b.ax, az = b.az) => HM.bathBox({ ...b, ax, az }, h);
  const clash = (b, h, ax, az) => { const p = bathPos(b, h, ax, az); return baths.some(o => o !== b && o.host === h.id && HM.boxOverlap(p, bathPos(o))); };
  const isBad = r => rooms.some(o => o !== r && HM.boxOverlap(r, o));
  const fitsBaths = r => baths.filter(b => b.host === r.id).every(b => b.w <= r.w + 1e-6 && b.d <= r.d + 1e-6 && !clash(b, r, b.ax, b.az));
  const newId = p => { let id; do id = p + (++uid); while (byId(id)); return id; };

  function open(t) {
    const b = HM.toBlocks(t); rooms = b.blocks; baths = b.baths; sel = null;
    startNames = new Map(baths.map(x => [x.id, x.name]));
    el.hidden = false; root.dataset.blocks = '1'; requestAnimationFrame(() => { fit(); render(); });
  }
  function close() { el.hidden = true; delete root.dataset.blocks; sel = null; drag = null; }
  function useTemplate(t, name) { const b = HM.toBlocks(t); rooms = b.blocks; baths = b.baths; sel = null; fit(); render(); toast(`已换成${name}，再拖方块调整`); }
  // 主卫 / 公卫 renames from moving a bathroom between rooms: the ledger's records follow the new name.
  function done() {
    const home = HM.sanitizeBlocks({ blocks: rooms, baths }); if (!home) { toast('至少要留一个房间'); return; }
    const renames = []; for (const b of home.baths) { const was = startNames.get(b.id); if (was && was !== b.name) renames.push([was, b.name]); }
    close(); onDone(home, renames);
  }

  /* ---------- layout ---------- */
  function bounds() { const xs = rooms.flatMap(r => [r.x, r.x + r.w]), zs = rooms.flatMap(r => [r.z, r.z + r.d]); return { x0: Math.min(...xs, 0), z0: Math.min(...zs, 0), x1: Math.max(...xs, 4), z1: Math.max(...zs, 4) }; }
  function fit() {
    const b = bounds(), pw = plan.clientWidth || 320, ph = plan.clientHeight || 400, pad = 14;
    S = Math.max(14, Math.min(40, (pw - pad * 2) / (b.x1 - b.x0 + 1), (ph - pad * 2) / (b.z1 - b.z0 + 1)));
    OX = pad + ((pw - pad * 2) - (b.x1 - b.x0) * S) / 2 - b.x0 * S; OZ = pad + ((ph - pad * 2) - (b.z1 - b.z0) * S) / 2 - b.z0 * S;
    plan.style.setProperty('--g', S + 'px'); plan.style.backgroundPosition = `${OX}px ${OZ}px`;
  }
  const px = x => OX + x * S, pz = z => OZ + z * S;
  function lblPos(r) {
    const bs = baths.filter(x => x.host === r.id && !x.drag); if (!bs.length) return 'left:50%;top:50%';
    const cand = []; for (let i = 1; i < 8; i++) for (let j = 1; j < 8; j++) cand.push([r.x + r.w * i / 8, r.z + r.d * j / 8]);
    const dist = ([x, z]) => Math.min(...bs.map(b => { const p = bathPos(b); return Math.hypot(Math.max(p.x - x, 0, x - p.x - p.w), Math.max(p.z - z, 0, z - p.z - p.d)); }), x - r.x, r.x + r.w - x, z - r.z, r.z + r.d - z);
    const c = cand.sort((m, n) => dist(n) - dist(m))[0];
    return `left:${((c[0] - r.x) / r.w * 100).toFixed(1)}%;top:${((c[1] - r.z) / r.d * 100).toFixed(1)}%`;
  }

  /* ---------- render ---------- */
  const els = new Map();
  function blk(id) { let e = els.get(id); if (!e) { e = document.createElement('div'); e.dataset.id = id; plan.appendChild(e); els.set(id, e); bindDrag(e); } return e; }
  const html = (e, h) => { if (e._h !== h) { e._h = h; e.innerHTML = h; } };
  function place(e, p) { Object.assign(e.style, { left: px(p.x) + 'px', top: pz(p.z) + 'px', width: p.w * S + 'px', height: p.d * S + 'px' }); }
  function render() {
    const live = new Set();
    for (const r of rooms) {
      const e = blk(r.id); live.add(r.id); e.className = 'pe-blk' + (r.type === '阳台' ? ' balcony' : r.type === '卫生间' ? ' wet' : '') + (sel === r.id ? ' sel' : '') + (r.bad ? ' bad' : '') + (drag && drag.it === r ? ' drag' : '');
      place(e, r); html(e, `<span class="pe-lbl" style="${lblPos(r)}">${esc(r.name)}<small>${fmtA(areaOf(r))}</small></span>`);
      e.setAttribute('aria-label', `${r.name} ${r.w.toFixed(1)} × ${r.d.toFixed(1)} 米`);
    }
    for (const b of baths) {
      const e = blk(b.id); live.add(b.id); e.className = 'pe-blk bath' + (sel === b.id ? ' sel' : '') + (b.bad ? ' bad' : '');
      place(e, b.drag || bathPos(b)); html(e, `${esc(b.name)}<small>${fmtA(b.w * b.d)}</small>`);
      e.setAttribute('aria-label', `${b.name}，在${host(b).name}里`);
    }
    for (const [id, e] of els) if (!live.has(id)) { e.remove(); els.delete(id); }
    plan.querySelectorAll('.pe-hdl').forEach(h => h.remove());
    const it = byId(sel);
    if (it && !drag) {
      const p = it.host ? bathPos(it) : it;
      const ks = it.host ? [['r', p.x + p.w, p.z + p.d / 2], ['b', p.x + p.w / 2, p.z + p.d]] : [['l', p.x, p.z + p.d / 2], ['r', p.x + p.w, p.z + p.d / 2], ['t', p.x + p.w / 2, p.z], ['b', p.x + p.w / 2, p.z + p.d]];
      for (const [k, x, z] of ks) { const h = document.createElement('div'); h.className = 'pe-hdl'; h.dataset.k = k; h.style.left = px(x) + 'px'; h.style.top = pz(z) + 'px'; h.setAttribute('aria-label', '拖动改大小'); plan.appendChild(h); bindHandle(h, it); }
    }
    const tot = rooms.filter(r => r.type !== '阳台').reduce((s, r) => s + r.w * r.d, 0);
    $('.pe-sum').textContent = `套内约 ${Math.round(tot)}㎡ · ${baths.length + rooms.filter(r => r.type === '卫生间').length} 个卫生间`;
    $('.pe-selbar').hidden = !it;
    if (it) { $('.pe-sel-n').textContent = it.name; $('.pe-sel-s').textContent = `${it.w.toFixed(1)} × ${it.d.toFixed(1)} 米 · ${fmtA(it.host ? it.w * it.d : areaOf(it))}${it.host ? ` · 在${host(it).name}里` : ''}`; }
  }
  function showLines(ls) {
    plan.querySelectorAll('.pe-line').forEach(l => l.remove());
    for (const [ax, v] of ls) { const l = document.createElement('div'); l.className = 'pe-line'; if (ax === 'x') Object.assign(l.style, { left: px(v) - 1 + 'px', top: 0, width: '2px', height: '100%' }); else Object.assign(l.style, { top: pz(v) - 1 + 'px', left: 0, height: '2px', width: '100%' }); plan.appendChild(l); }
  }
  function showAnchors(h, b) {
    plan.querySelectorAll('.pe-anchor').forEach(a => a.remove()); if (!h) return;
    for (const ax of [0, .5, 1]) for (const az of [0, .5, 1]) { if (clash(b, h, ax, az)) continue; const a = document.createElement('div'); a.className = 'pe-anchor'; a.style.left = px(h.x + ax * (h.w - b.w) + b.w / 2) + 'px'; a.style.top = pz(h.z + az * (h.d - b.d) + b.d / 2) + 'px'; plan.appendChild(a); }
  }

  /* ---------- snapping ---------- */
  function snapMove(r) {
    let bx = null, bz = null; const ls = [];
    for (const o of rooms) { if (o === r) continue;
      for (const [me, them] of [[r.x, o.x], [r.x, o.x + o.w], [r.x + r.w, o.x], [r.x + r.w, o.x + o.w]]) { const d = them - me; if (Math.abs(d) < SNAP && (!bx || Math.abs(d) < Math.abs(bx.d))) bx = { d, v: them }; }
      for (const [me, them] of [[r.z, o.z], [r.z, o.z + o.d], [r.z + r.d, o.z], [r.z + r.d, o.z + o.d]]) { const d = them - me; if (Math.abs(d) < SNAP && (!bz || Math.abs(d) < Math.abs(bz.d))) bz = { d, v: them }; } }
    if (bx) { r.x = r2(r.x + bx.d); ls.push(['x', bx.v]); } if (bz) { r.z = r2(r.z + bz.d); ls.push(['z', bz.v]); }
    return ls;
  }
  function snapEdge(r, k) {
    const ax = k === 'l' || k === 'r' ? 'x' : 'z', v = k === 'l' ? r.x : k === 'r' ? r.x + r.w : k === 't' ? r.z : r.z + r.d; let best = null;
    for (const o of rooms) { if (o === r) continue; for (const t of ax === 'x' ? [o.x, o.x + o.w] : [o.z, o.z + o.d]) { const d = t - v; if (Math.abs(d) < SNAP && (!best || Math.abs(d) < Math.abs(best.d))) best = { d, t }; } }
    if (!best) return [];
    if (k === 'l') { r.w -= best.d; r.x += best.d; } else if (k === 'r') r.w += best.d; else if (k === 't') { r.d -= best.d; r.z += best.d; } else r.d += best.d;
    return [[ax, best.t]];
  }
  const q3 = v => v < .25 ? 0 : v > .75 ? 1 : .5;
  function dropHost(b) {
    const cx = b.drag.x + b.w / 2, cz = b.drag.z + b.d / 2;
    const h = rooms.find(o => !HM.NOHOST.has(o.type) && cx > o.x && cx < o.x + o.w && cz > o.z && cz < o.z + o.d && b.w <= o.w && b.d <= o.d); if (!h) return null;
    const ax = q3(h.w > b.w ? (b.drag.x - h.x) / (h.w - b.w) : .5), az = q3(h.d > b.d ? (b.drag.z - h.z) / (h.d - b.d) : .5);
    return clash(b, h, ax, az) ? { h, clash: 1 } : { h, ax, az };
  }
  function rename(b) { b.name = HM.nextName(HM.bathName(host(b)), used(b)); }

  /* ---------- gestures: touch first, mouse as a fallback ---------- */
  function track(target, start) {
    target.addEventListener('touchstart', ev => { if (ev.touches.length > 1) return; ev.preventDefault(); ev.stopPropagation(); const t = ev.touches[0], g = start(t.clientX, t.clientY); if (!g) return;
      const mv = e => { const u = [...e.touches].find(x => x.identifier === t.identifier); if (u) { e.preventDefault(); g.move(u.clientX, u.clientY); } };
      // On the document: a re-render may replace the node the finger first touched, and its events would stop there.
      const up = e => { if ([...e.touches].some(x => x.identifier === t.identifier)) return; document.removeEventListener('touchmove', mv); document.removeEventListener('touchend', up); document.removeEventListener('touchcancel', up); g.end(); };
      document.addEventListener('touchmove', mv, { passive: false }); document.addEventListener('touchend', up); document.addEventListener('touchcancel', up); }, { passive: false });
    target.addEventListener('mousedown', ev => { if (ev.button) return; ev.preventDefault(); ev.stopPropagation(); const g = start(ev.clientX, ev.clientY); if (!g) return;
      const mv = e => g.move(e.clientX, e.clientY), up = () => { removeEventListener('mousemove', mv); removeEventListener('mouseup', up); g.end(); };
      addEventListener('mousemove', mv); addEventListener('mouseup', up); });
  }
  function bindDrag(e) {
    track(e, (sx, sy) => {
      const it = byId(e.dataset.id); if (!it) return null; sel = it.id;
      const o = it.host ? bathPos(it) : { x: it.x, z: it.z }; drag = { it, moved: false }; render();
      return {
        move(x, y) {
          const dx = (x - sx) / S, dz = (y - sy) / S; if (Math.hypot(dx, dz) * S > 4) drag.moved = true; if (!drag.moved) return;
          if (!it.host) { it.x = r1(o.x + dx); it.z = r1(o.z + dz); showLines(snapMove(it)); it.bad = isBad(it); }
          else { it.drag = { x: o.x + dx, z: o.z + dz }; const d = dropHost(it); it.bad = !d || !!d.clash; showAnchors(d && d.h, it); }
          render();
        },
        end() {
          showLines([]); showAnchors(null);
          if (!it.host && it.bad) { it.x = o.x; it.z = o.z; it.bad = false; toast('房间不能叠在一起，已放回原位'); }
          if (it.host && it.drag) { const d = dropHost(it);
            if (d && !d.clash) { const was = it.name, moved = d.h.id !== it.host; it.host = d.h.id; it.ax = d.ax; it.az = d.az; if (moved) { rename(it); toast(it.name === was ? `${was}改放在${d.h.name}里` : `${was}改放在${d.h.name}里，现在叫${it.name}`); } }
            else toast(d ? '跟另一个卫生间叠到了，已放回原位' : '卫生间要放在某个房间里');
            it.drag = null; it.bad = false; }
          drag = null; fit(); render();
        }
      };
    });
  }
  function bindHandle(h, it) {
    track(h, (sx, sy) => {
      const k = h.dataset.k, o = { x: it.x, z: it.z, w: it.w, d: it.d }, MIN = HM.BLOCK_MIN; drag = { it, moved: true, handle: true };
      return {
        move(x, y) {
          const dx = r1((x - sx) / S), dz = r1((y - sy) / S);
          if (it.host) { const hr = host(it); if (k === 'r') it.w = r2(Math.min(hr.w, Math.max(HM.BATH_MIN, o.w + dx))); else it.d = r2(Math.min(hr.d, Math.max(HM.BATH_MIN, o.d + dz))); it.bad = clash(it, hr, it.ax, it.az); render(); return; }
          Object.assign(it, o);
          if (k === 'l') { const v = Math.min(dx, o.w - MIN); it.x = o.x + v; it.w = o.w - v; } else if (k === 'r') it.w = Math.max(MIN, o.w + dx);
          else if (k === 't') { const v = Math.min(dz, o.d - MIN); it.z = o.z + v; it.d = o.d - v; } else it.d = Math.max(MIN, o.d + dz);
          showLines(snapEdge(it, k)); it.x = r2(it.x); it.z = r2(it.z); it.w = Math.max(MIN, r2(it.w)); it.d = Math.max(MIN, r2(it.d)); it.bad = isBad(it) || !fitsBaths(it); render();
        },
        end() { showLines([]); if (it.bad) { Object.assign(it, o); it.bad = false; toast(it.host ? '会跟另一个卫生间叠到，已改回原来大小' : '这样会叠到别的房间，已改回原来大小'); } drag = null; fit(); render(); }
      };
    });
  }
  plan.addEventListener('click', e => { if (e.target === plan) { sel = null; render(); } });

  /* ---------- ➕ add, 拿掉 ---------- */
  function freeSpot(w, d) {
    const b = bounds();
    for (let z = b.z0; z <= b.z1 + .01; z += .3) for (let x = b.x0; x <= b.x1 + .01; x += .3) { const c = { x: r1(x), z: r1(z), w, d }; if (!rooms.some(o => HM.boxOverlap(c, o))) return c; }
    return { x: r1(b.x1), z: r1(b.z0), w, d };
  }
  function bathSpot(b, h) { for (const [ax, az] of [[1, 0], [0, 0], [1, 1], [0, 1], [.5, 0], [.5, 1], [0, .5], [1, .5], [.5, .5]]) if (!clash(b, h, ax, az)) return { ax, az }; return null; }
  const size = t => { const s = HM.SPACE_TYPES.find(x => x[0] === t); return { w: s[1], d: s[2] }; };
  function addBath() {
    const b = { id: newId('w'), ...size('卫生间'), host: null, ax: 1, az: 0 };
    const it = byId(sel), picked = it ? (it.host ? host(it) : it) : null;
    const ok = h => h && !HM.NOHOST.has(h.type) && b.w <= h.w && b.d <= h.d && bathSpot(b, h);
    let h = ok(picked) ? picked : null;
    if (!h && picked) toast(`${picked.name}放不下，改放到别的房间`);
    if (!h) h = rooms.filter(ok).sort((m, n) => (baths.filter(x => x.host === m.id).length - baths.filter(x => x.host === n.id).length) || (n.w * n.d - m.w * m.d))[0];
    if (!h) { toast('没有房间放得下，先拉大一个房间'); return; }
    Object.assign(b, { host: h.id }, bathSpot(b, h)); b.name = HM.nextName(HM.bathName(h), used()); baths.push(b); sel = b.id; render();
    if (!picked || h === picked) toast(`加了${b.name}，放在${h.name}里，可以拖到别处`);
  }
  function addRoom(type) { const s = size(type), r = { id: newId('b'), type, name: HM.nextName(type, used()), ...freeSpot(s.w, s.d) }; rooms.push(r); sel = r.id; fit(); render(); toast(`加了${r.name}，拖去贴着别的房间`); }
  function remove() {
    const it = byId(sel); if (!it) return;
    if (!it.host) { const inside = baths.filter(x => x.host === it.id); if (inside.length) { toast(`先把${inside.map(x => x.name).join('、')}拖到别的房间或拿掉`); return; } if (rooms.length < 2) { toast('至少要留一个房间'); return; } rooms = rooms.filter(o => o !== it); }
    else baths = baths.filter(o => o !== it);
    sel = null; fit(); render(); toast(`拿掉了${it.name}，里面的东西回到「还没放进家里的」`);
  }
  el.addEventListener('click', e => {
    const b = e.target.closest('[data-pe]'); if (!b) return; const a = b.dataset.pe;
    if (a === 'done') done();
    else if (a === 'tpl') onTemplates();
    else if (a === 'del') remove();
    else if (a === 'add') { const it = byId(sel); sheet.querySelector('.pe-sheet-hint').textContent = it && !it.host && !HM.NOHOST.has(it.type) ? `选「卫生间」会放在${it.name}里；其他放在空位，再拖去拼` : '选好后放在空位，再拖去贴着别的房间'; openSheet('space'); }
  });
  sheet.addEventListener('click', e => { const b = e.target.closest('[data-space]'); if (!b) return; closeSheet(sheet); b.dataset.space === '卫生间' ? addBath() : addRoom(b.dataset.space); });
  addEventListener('resize', () => { if (!el.hidden) { fit(); render(); } });
  return { open, close, done, useTemplate, isOpen: () => !el.hidden, debug: () => ({ rooms, baths, S, OX, OZ }) };
}
