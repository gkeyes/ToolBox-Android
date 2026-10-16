// 1.4.0 · 「我的家」 3D home. Loaded on demand by app.js (dynamic import) only when the 总览 card or the
// 我的家 tab is shown, so the ledger pages start as before. One WebGL renderer serves the card, the tab and
// the look thumbnails: its canvas moves between them, so the page never holds a second context.
// Renders on demand; touch events drive the camera and dragging because some WebViews send no pointer events.
import * as THREE from './vendor/three.module.min.js';
import { RoundedBoxGeometry } from './vendor/RoundedBoxGeometry.js';
import { mergeGeometries } from './vendor/BufferGeometryUtils.js';

const HM = window.ZZHomeModel;
const PI = Math.PI;
const WALL_H = 2.5, WT = HM.WALL_T;
const POL_MIN = .35, POL_MAX = 1.12, ZOOM_MIN = .75, ZOOM_MAX = 3.2;
const VIEW = { az: PI / 4, pol: .98, zoom: 1 };
const svg = (d, cls = 'i') => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${d}</svg>`;
const ICON = {
  place: '<path d="M5 9V5h4M19 9V5h-4M5 15v4h4M19 15v4h-4"/><rect x="9" y="9" width="6" height="6" rx="1"/>',
  layout: '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 11h9V4M13 15v5"/>',
  reset: '<path d="M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4"/>',
  rot: '<path d="M20 12a8 8 0 1 1-2.4-5.7M20 4v4h-4"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  tag: '<path d="M3 12V5a2 2 0 0 1 2-2h7l9 9-9 9-9-9z"/><circle cx="8" cy="8" r="1.3"/>',
  tagOff: '<path d="M3 12V5a2 2 0 0 1 2-2h7l9 9-9 9-9-9z"/><circle cx="8" cy="8" r="1.3"/><path d="M3 3l18 18"/>',
  hx: '<path d="M8 8l-4 4 4 4M16 8l4 4-4 4M4 12h16"/>',
  hz: '<path d="M8 8l4-4 4 4M8 16l4 4 4-4M12 4v16"/>'
};

/* ================= materials & primitives (from the approved design) ================= */
const mats = {};
function M(c, o = {}) {
  const k = c + '|' + (o.r ?? '') + (o.m ?? '') + (o.t ?? '') + (o.e ?? '') + (o.ei ?? '');
  if (mats[k]) return mats[k];
  return mats[k] = new THREE.MeshStandardMaterial({ color: c, roughness: o.r ?? .72, metalness: o.m ?? 0, transparent: o.t !== undefined, opacity: o.t ?? 1, emissive: o.e || '#000000', emissiveIntensity: o.ei ?? 1 });
}
function B(w, h, d, c, x = 0, y = 0, z = 0, o = {}) {
  const r = Math.min(o.rad ?? .025, w / 2 - .002, h / 2 - .002, d / 2 - .002);
  const g = r > .006 ? new RoundedBoxGeometry(w, h, d, 2, r) : new THREE.BoxGeometry(w, h, d);
  const m = new THREE.Mesh(g, M(c, o));
  m.position.set(x, y + h / 2, z); if (o.rx) m.rotation.x = o.rx; m.castShadow = m.receiveShadow = true; return m;
}
function C(r, h, c, x = 0, y = 0, z = 0, o = {}) {
  const m = new THREE.Mesh(new THREE.CylinderGeometry(o.r2 ?? r, r, h, o.seg ?? 24), M(c, o));
  m.position.set(x, y + h / 2, z); if (o.rx) m.rotation.x = o.rx; m.castShadow = m.receiveShadow = true; return m;
}
function S(r, c, x = 0, y = 0, z = 0, o = {}) {
  const m = new THREE.Mesh(new THREE.SphereGeometry(r, o.seg ?? 16, 12), M(c, o));
  m.position.set(x, y, z); if (o.sy) m.scale.y = o.sy; m.castShadow = m.receiveShadow = true; return m;
}
function G(...ch) { const g = new THREE.Group(); ch.flat().forEach(c => g.add(c)); return g; }
const legs = (w, d, h, c, inset = .06, s = .04) => [[-1, -1], [1, -1], [-1, 1], [1, 1]].map(([a, b]) => B(s, h, s, c, a * (w / 2 - inset), 0, b * (d / 2 - inset), { rad: 0 }));
function chair(c, seatY = .44) { return G(B(.42, .05, .42, c, 0, seatY, 0, { rad: .02 }), B(.42, .42, .05, c, 0, seatY, -.19, { rad: .02 }), legs(.42, .42, seatY, '#5a4632', .04, .035)); }

/* 13 appliance and 12 furniture looks, footprints in home-model.js */
const BUILD = {
  fridgeA: () => G(B(.7, 1.8, .68, '#f2f0ec', 0, 0, 0, { rad: .05 }), B(.66, .012, .01, '#bdb9b2', 0, 1.15, .342, { rad: 0 }), B(.03, .32, .04, '#a9adb1', -.27, 1.24, .36, { m: .5, r: .3 }), B(.03, .32, .04, '#a9adb1', -.27, .74, .36, { m: .5, r: .3 })),
  fridgeB: () => G(B(.92, 1.82, .7, '#c3c8cc', 0, 0, 0, { m: .55, r: .32, rad: .04 }), B(.012, 1.78, .01, '#7d848a', 0, .02, .352, { rad: 0 }), B(.03, .6, .04, '#e9ecee', -.06, .7, .37, { m: .7, r: .2 }), B(.03, .6, .04, '#e9ecee', .06, .7, .37, { m: .7, r: .2 }), B(.14, .08, .01, '#1f2c31', -.24, 1.3, .355, { e: '#5fd0c0', ei: .7, rad: 0 })),
  fridgeC: () => G(B(.66, 1.5, .64, '#a9d8c6', 0, 0, 0, { rad: .13 }), B(.6, .012, .01, '#86b3a2', 0, 1.04, .322, { rad: 0 }), B(.05, .2, .05, '#e6e9eb', .24, 1.12, .34, { m: .8, r: .2 }), B(.05, .2, .05, '#e6e9eb', .24, .78, .34, { m: .8, r: .2 })),
  tvA: () => G(B(1.7, .42, .42, '#c9a37a', 0, .04, 0), legs(1.7, .42, .06, '#5b4430'), B(.82, .01, .01, '#9c7a55', -.41, .24, .212, { rad: 0 }), B(.82, .01, .01, '#9c7a55', .41, .24, .212, { rad: 0 }), B(.3, .1, .18, '#2a2d30', 0, .46, 0), B(1.3, .76, .04, '#16191c', 0, .56, 0, { r: .3 }), B(1.24, .7, .005, '#21374a', 0, .59, .022, { e: '#3b6d97', ei: .35, rad: 0 })),
  tvB: () => G(B(.5, .04, .42, '#d6d0c6'), C(.025, .9, '#d6d0c6', 0, .04, 0), B(1.1, .66, .05, '#f2efe9', 0, .78, .02, { rad: .03 }), B(1.02, .58, .005, '#2d4a6a', 0, .82, .047, { e: '#4a86b5', ei: .4, rad: 0 })),
  acA: () => G(B(.9, .3, .22, '#f6f6f3', 0, 1.95, 0, { rad: .09 }), B(.8, .025, .02, '#cfd3d2', 0, 1.97, .105, { rad: 0 }), B(.06, .02, .005, '#79d3b0', .36, 2.15, .11, { e: '#79d3b0', ei: .8, rad: 0 })),
  acB: () => G(B(.5, 1.75, .34, '#f3f3f0', 0, 0, 0, { rad: .09 }), ...[0, 1, 2, 3, 4, 5].map(i => B(.36, .012, .01, '#c9cdcc', 0, 1.15 + i * .07, .171, { rad: 0 })), B(.12, .05, .005, '#1d2a30', 0, .95, .172, { e: '#62c8f0', ei: .7, rad: 0 })),
  hoodA: () => G(B(.9, .14, .5, '#c3c7ca', 0, 1.62, 0, { m: .6, r: .3 }), B(.32, .8, .26, '#c3c7ca', 0, 1.76, -.12, { m: .6, r: .3 })),
  hoodB: () => G(B(.9, .62, .26, '#b9bec2', 0, 1.5, -.07, { m: .5, r: .35 }), B(.88, .5, .03, '#16191c', 0, 1.52, .1, { r: .15, rx: -.45 })),
  microA: () => G(B(.6, .86, .45, '#ebe6de'), B(.01, .6, .01, '#c7bfb2', 0, .12, .226, { rad: 0 }), B(.62, .04, .47, '#b9a68b', 0, .86, 0, { rad: .01 }), B(.5, .3, .38, '#2a2d31', 0, .9, 0), B(.32, .22, .01, '#0f1418', -.06, .94, .19, { r: .12, rad: 0 }), B(.1, .22, .01, '#565b60', .18, .94, .19, { rad: 0 })),
  microB: () => G(B(.62, 2.0, .58, '#d8cbb8'), B(.5, .3, .03, '#222629', 0, 1.15, .29), B(.3, .2, .005, '#0f1418', -.06, 1.2, .306, { rad: 0 }), B(.5, .46, .03, '#222629', 0, .58, .29), B(.4, .01, .02, '#9aa0a5', 0, .98, .3, { m: .7, rad: 0 })),
  washerA: () => { const t = new THREE.Mesh(new THREE.TorusGeometry(.17, .028, 10, 32), M('#c3c8cb', { m: .6, r: .3 })); t.position.set(0, .42, .3); const gl = C(.15, .02, '#3a4f60', 0, .41, .3, { rx: PI / 2, r: .15 }); gl.position.set(0, .42, .3); return G(B(.6, .85, .6, '#f4f4f2'), B(.58, .08, .01, '#dfe1e0', 0, .72, .3, { rad: 0 }), t, gl); },
  washerB: () => G(B(.6, .88, .6, '#d9dde0'), B(.5, .03, .4, '#aab4bb', 0, .88, .05), B(.6, .14, .08, '#c4c9cd', 0, .88, -.26), B(.14, .05, .005, '#1d2a30', 0, .93, -.218, { e: '#62c8f0', ei: .6, rad: 0 })),
  sofaA: () => { const c = '#3f6b45', s = '#4b7c51'; return G(B(2.1, .42, .95, c, 0, 0, 0, { rad: .13 }), B(2.1, .44, .3, c, 0, .36, -.32, { rad: .14 }), B(.3, .58, .95, c, -.9, 0, 0, { rad: .14 }), B(.3, .58, .95, c, .9, 0, 0, { rad: .14 }), B(.74, .13, .64, s, -.38, .4, .1, { rad: .06 }), B(.74, .13, .64, s, .38, .4, .1, { rad: .06 }), B(.6, .05, .7, '#e2d6c2', .5, .53, .05, { rad: .02 })); },
  sofaB: () => { const c = '#8d9196', s = '#9da1a6'; return G(B(2.3, .4, .9, c, 0, 0, -.35, { rad: .08 }), B(.85, .4, .75, c, .72, 0, .42, { rad: .08 }), B(2.3, .42, .22, c, 0, .34, -.69, { rad: .08 }), B(.22, .5, .9, c, -1.04, 0, -.35, { rad: .08 }), B(.7, .11, .62, s, -.6, .4, -.28, { rad: .05 }), B(.7, .11, .62, s, .1, .4, -.28, { rad: .05 }), B(.7, .11, 1.3, s, .72, .4, .12, { rad: .05 }), B(.35, .3, .12, '#d58f5a', -.75, .5, -.5, { rad: .05 })); },
  sofaC: () => { const c = '#d9c7ad'; return G(legs(1.5, .85, .13, '#7a5a3a', .1, .04), B(1.5, .28, .85, c, 0, .12, 0, { rad: .08 }), B(1.5, .46, .2, c, 0, .36, -.32, { rad: .09 }), B(.18, .3, .85, c, -.66, .38, 0, { rad: .08 }), B(.18, .3, .85, c, .66, .38, 0, { rad: .08 }), B(.32, .28, .1, '#3d5a8a', -.35, .42, -.18, { rad: .05 })); },
  bedA: () => G(B(1.7, .3, 2.15, '#b48a5e'), B(1.7, .95, .08, '#a17a50', 0, 0, -1.04), B(1.6, .22, 2.0, '#f4f1ea', 0, .3, .04, { rad: .08 }), B(1.64, .07, 1.25, '#d98f5c', 0, .48, .42, { rad: .04 }), B(.6, .13, .34, '#ffffff', -.4, .52, -.72, { rad: .06 }), B(.6, .13, .34, '#ffffff', .4, .52, -.72, { rad: .06 })),
  bedB: () => G(B(1.75, .32, 2.2, '#9aa1a7', 0, 0, 0, { rad: .06 }), B(1.75, 1.15, .16, '#9aa1a7', 0, 0, -1.02, { rad: .08 }), B(1.62, .22, 2.0, '#eef0f2', 0, .32, .06, { rad: .08 }), B(1.66, .07, 1.2, '#7f9cb5', 0, .5, .45, { rad: .04 }), B(.6, .13, .34, '#f7f3ea', -.4, .54, -.7, { rad: .06 }), B(.6, .13, .34, '#f7f3ea', .4, .54, -.7, { rad: .06 })),
  diningA: () => { const g = G(C(.52, .045, '#c69c6d', 0, .72, 0, { seg: 36 }), C(.06, .72, '#a57d52'), C(.26, .03, '#a57d52', 0, 0, 0, { seg: 30 })); for (let i = 0; i < 4; i++) { const ch = chair('#e7d9c2'); const a = i * PI / 2 + PI / 4; ch.position.set(Math.sin(a) * .72, 0, Math.cos(a) * .72); ch.rotation.y = a + PI; g.add(ch); } return g; },
  diningB: () => { const g = G(B(1.4, .05, .8, '#f2f2ef', 0, .72, 0, { rad: .02 }), legs(1.4, .8, .72, '#2b2b2b')); [[-.35, -.62, 0], [.35, -.62, 0], [-.35, .62, PI], [.35, .62, PI]].forEach(([x, z, r]) => { const ch = chair('#2f4f6f'); ch.position.set(x, 0, z); ch.rotation.y = r; g.add(ch); }); return g; },
  deskA: () => { const ch = G(B(.48, .06, .46, '#2f4a9a', 0, .45, 0, { rad: .03 }), B(.48, .46, .06, '#2f4a9a', 0, .5, .2, { rad: .05 }), legs(.48, .46, .45, '#2f4a9a', .04, .04)); ch.position.z = .28; return G(B(1.2, .04, .6, '#c8a27a', 0, .72, -.22, { rad: .01 }), legs(1.2, .6, .72, '#c8a27a', .04, .04).map(l => (l.position.z -= .22, l)), B(.5, .3, .03, '#1d2024', 0, .8, -.4), B(.46, .26, .005, '#2f4f6a', 0, .82, -.383, { e: '#5d93c0', ei: .4, rad: 0 }), ch); },
  deskB: () => { const ch = chair('#e9e5dd'); ch.position.z = .3; ch.rotation.y = PI; const books = [...Array(6)].map((_, i) => B(.05, .18 + (i % 3) * .03, .16, ['#d0624d', '#3d6aa3', '#e2b84a', '#5c8a5a', '#8a5c8a', '#e8e2d6'][i], -.5 + i * .07, 1.33, -.2, { rad: 0 })); return G(B(1.3, .04, .6, '#f1efea', 0, .72, -.2, { rad: .01 }), legs(1.3, .6, .72, '#f1efea', .04, .04).map(l => (l.position.z -= .2, l)), B(1.3, .03, .24, '#f1efea', 0, 1.3, -.2, { rad: 0 }), B(.03, .58, .24, '#f1efea', -.635, .74, -.2, { rad: 0 }), B(.03, .58, .24, '#f1efea', .635, .74, -.2, { rad: 0 }), books, ch); },
  cabA: () => G(B(1.4, 2.1, .6, '#cfae86'), B(.01, 2.0, .01, '#8f7252', -.233, .05, .301, { rad: 0 }), B(.01, 2.0, .01, '#8f7252', .233, .05, .301, { rad: 0 }), ...[-.29, -.18, .18, .29].map(x => B(.02, .16, .02, '#6b5238', x, 1.0, .31, { rad: 0 }))),
  cabB: () => G(legs(1.3, .45, .14, '#2b2b2b', .06, .03), B(1.3, .72, .45, '#e2b84a', 0, .14, 0, { rad: .03 }), ...[0, 1, 2, 3, 4, 5].map(i => B(.5, .035, .01, '#2b2f33', .3, .22 + i * .09, .226, { rad: 0 })), B(.5, .55, .01, '#d6aa3c', -.3, .22, .226, { rad: 0 })),
  cabC: () => { const c = '#efe8dc', parts = [B(.03, 1.8, .34, c, -.485), B(.03, 1.8, .34, c, .485), ...[0, 1, 2, 3, 4].map(i => B(1.0, .03, .34, c, 0, i * .44, 0, { rad: 0 })), B(1, 1.8, .01, '#e3dbcd', 0, 0, -.165, { rad: 0 })]; const cols = ['#d0624d', '#3d6aa3', '#e2b84a', '#5c8a5a', '#2f2f33', '#e8a2a0', '#7aa7c7']; let s = 7; const rnd = () => (s = (s * 9301 + 49297) % 233280) / 233280; for (let r = 0; r < 4; r++) { let x = -.44; while (x < .4) { const w = .04 + rnd() * .05, h = .26 + rnd() * .12; parts.push(B(w, h, .24, cols[Math.floor(rnd() * cols.length)], x + w / 2, r * .44 + .03, 0, { rad: 0 })); x += w + .008; if (rnd() < .12) x += .12; } } return G(parts); },
  // 1.8.0 · tables, chairs and cabinets (front faces +z, backs -z)
  coffeeA: () => G(B(1.1, .05, .6, '#c69c6d', 0, .38, 0, { rad: .02 }), legs(1.1, .6, .38, '#8a6a48'), B(1.0, .03, .5, '#b88d60', 0, .12, 0, { rad: .01 }), B(.22, .06, .16, '#3d6aa3', -.25, .43, .05, { rad: .01 })),
  coffeeB: () => G(C(.4, .04, '#efe9df', 0, .42, 0, { seg: 36 }), C(.04, .42, '#2b2b2b'), C(.2, .02, '#2b2b2b', 0, 0, 0, { seg: 30 }), C(.08, .1, '#7aa7c7', .12, .46, .05, { r2: .05 })),
  sideA: () => G(C(.22, .03, '#c69c6d', 0, .55, 0, { seg: 30 }), C(.02, .55, '#2b2b2b'), C(.15, .02, '#2b2b2b', 0, 0, 0, { seg: 24 })),
  tableA: () => G(B(1.2, .04, .7, '#d9c3a0', 0, .72, 0, { rad: .01 }), legs(1.2, .7, .72, '#8a6a48', .05, .045)),
  chairA: () => chair('#c69c6d'),
  stoolA: () => G(C(.17, .04, '#c69c6d', 0, .45, 0, { seg: 24 }), legs(.28, .28, .45, '#8a6a48', .03, .03)),
  armA: () => { const c = '#c98b5e'; return G(B(.8, .4, .8, c, 0, 0, 0, { rad: .1 }), B(.8, .45, .18, c, 0, .32, -.31, { rad: .08 }), B(.13, .22, .8, c, -.335, .36, 0, { rad: .06 }), B(.13, .22, .8, c, .335, .36, 0, { rad: .06 }), B(.52, .1, .56, '#d9a074', 0, .4, .07, { rad: .05 })); },
  shoeA: () => G(B(1.0, 1.0, .32, '#efe8dc', 0, .06, -.015, { rad: .02 }), legs(1.0, .35, .06, '#2b2b2b', .05, .03), B(.01, .9, .01, '#cfc6b6', 0, .11, .15, { rad: 0 }), B(.02, .14, .02, '#8f7252', -.05, .5, .16, { rad: 0 }), B(.02, .14, .02, '#8f7252', .05, .5, .16, { rad: 0 })),
  dresserA: () => G(B(1.0, .04, .45, '#f1efea', 0, .74, 0, { rad: .01 }), legs(1.0, .45, .74, '#f1efea', .04, .035), B(.42, .14, .4, '#e6e1d8', .25, .58, 0, { rad: .01 }), B(.56, .7, .03, '#e6e1d8', 0, .78, -.2, { rad: .02 }), B(.48, .62, .01, '#cfe3ec', 0, .82, -.18, { m: .6, r: .08, rad: 0 })),
  // 生活: lamps, the cat's things, plants
  lampA: () => G(C(.16, .03, '#2b2b2b', -.05, 0, 0, { seg: 28 }), C(.014, 1.55, '#2b2b2b', -.12), B(.26, .02, .02, '#2b2b2b', 0, 1.53, 0, { rad: 0 }), C(.13, .16, '#f3ead8', .1, 1.36, 0, { r2: .06, e: '#ffd9a0', ei: .35 })),
  lampB: () => G(C(.13, .03, '#d6d0c6', 0, 0, 0, { seg: 28 }), C(.013, 1.32, '#d6d0c6'), C(.19, .3, '#efe2c8', 0, 1.3, 0, { r2: .15, e: '#ffcf8f', ei: .3 })),
  catTree: () => { const p = '#c9ad7a', w = '#e6dcc8'; return G(B(.6, .06, .6, '#cdbb9e', 0, 0, 0, { rad: .02 }), C(.05, .9, p, -.2, .06, -.2), C(.05, .9, p, .2, .06, -.2), B(.34, .3, .34, '#bfa985', .1, .06, .1, { rad: .04 }), C(.08, .005, '#3a2f25', .1, .19, .272, { rx: PI / 2, seg: 20 }), B(.56, .04, .36, w, 0, .96, -.1, { rad: .02 }), C(.05, .45, p, -.11, 1.0, -.11), C(.19, .06, w, -.11, 1.45, -.11, { seg: 24 }), C(.15, .05, '#f4ece0', -.11, 1.49, -.11, { seg: 24 })); },
  catBed: () => G(C(.26, .14, '#d9a46b', 0, 0, 0, { seg: 30 }), C(.21, .06, '#f2e6d3', 0, .1, 0, { seg: 28 }), S(.09, '#8f8a84', .04, .2, .02, { sy: .55 })),
  plantA: () => G(C(.14, .32, '#c96f4a', 0, 0, 0, { r2: .18, seg: 24 }), C(.16, .02, '#5a4632', 0, .3, 0, { seg: 24 }), S(.22, '#4f8a4b', 0, .66, 0), S(.16, '#5e9c57', .06, .88, .05), S(.14, '#447a41', -.08, .98, -.04)),
  plantB: () => G(C(.12, .5, '#efe9df', 0, 0, 0, { r2: .14, seg: 24 }), ...[0, 1, 2, 3, 4].map(i => { const a = i * 1.25, l = B(.05, .7, .02, i % 2 ? '#3f7a3c' : '#5a9a50', Math.sin(a) * .04, .48, Math.cos(a) * .04, { rad: 0 }); l.rotation.z = Math.sin(a) * .25; l.rotation.x = Math.cos(a) * .25; return l; }))
};

// Merge every model into one mesh per material (shared by all its instances), so a full home stays near 150 draw calls.
function mergeByMaterial(root) {
  root.updateMatrixWorld(true);
  const groups = new Map();
  root.traverse(o => {
    if (!o.isMesh) return;
    let g = o.geometry.clone().applyMatrix4(o.matrixWorld);
    if (g.index) g = g.toNonIndexed();
    for (const name of Object.keys(g.attributes)) if (!['position', 'normal', 'uv'].includes(name)) g.deleteAttribute(name);
    if (!groups.has(o.material)) groups.set(o.material, []);
    groups.get(o.material).push(g);
    o.geometry.dispose();
  });
  return [...groups].map(([mat, list]) => { const geo = mergeGeometries(list, false); list.forEach(g => g.dispose()); return { geo, mat }; });
}
const PARTS = {};
const partsOf = kind => PARTS[kind] || (PARTS[kind] = mergeByMaterial(BUILD[kind]()));
const ghosts = new Map();
function ghostOf(mat) {
  if (!ghosts.has(mat)) { const g = mat.clone(); g.transparent = true; g.opacity = .45; g.depthWrite = true; ghosts.set(mat, g); }
  return ghosts.get(mat);
}
function instance(kind, ghost) {
  const g = new THREE.Group();
  for (const { geo, mat } of partsOf(kind)) {
    const m = new THREE.Mesh(geo, ghost ? ghostOf(mat) : mat);
    m.castShadow = !ghost; m.receiveShadow = true; g.add(m);
  }
  return g;
}

/* ================= textures ================= */
function canvasTex(draw, s = 256) { const c = document.createElement('canvas'); c.width = c.height = s; draw(c.getContext('2d'), s); const t = new THREE.CanvasTexture(c); t.wrapS = t.wrapT = THREE.RepeatWrapping; t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 4; return t; }
function woodTex(tones) { return canvasTex((g, s) => { const n = 8, h = s / n; for (let i = 0; i < n; i++) { let x = -(i * 97 % 90); while (x < s) { const w = 80 + ((i * 31 + x) % 60); g.fillStyle = tones[(i + Math.floor(x / 25)) % tones.length]; g.fillRect(x, i * h, w, h); g.fillStyle = 'rgba(0,0,0,.08)'; g.fillRect(x, i * h, 1, h); x += w; } g.fillStyle = 'rgba(0,0,0,.10)'; g.fillRect(0, i * h, s, 1); } }); }
let TEX = null;
function textures() {
  if (TEX) return TEX;
  TEX = {
    oak: woodTex(['#c99a6b', '#c39060', '#cfa377', '#bf8d5f']),
    light: woodTex(['#dcc19c', '#d4b68e', '#e2c9a6', '#d8bc96']),
    tile: canvasTex((g, s) => { g.fillStyle = '#e9e6e0'; g.fillRect(0, 0, s, s); g.strokeStyle = '#cfcac2'; g.lineWidth = 2; for (let i = 0; i <= 4; i++) { g.beginPath(); g.moveTo(i * s / 4, 0); g.lineTo(i * s / 4, s); g.stroke(); g.beginPath(); g.moveTo(0, i * s / 4); g.lineTo(s, i * s / 4); g.stroke(); } }),
    deck: canvasTex((g, s) => { for (let i = 0; i < 10; i++) { g.fillStyle = i % 2 ? '#a77b55' : '#9d714c'; g.fillRect(0, i * s / 10, s, s / 10); g.fillStyle = 'rgba(0,0,0,.18)'; g.fillRect(0, i * s / 10, s, 2); } }),
    terr: canvasTex((g, s) => { g.fillStyle = '#d8d2c8'; g.fillRect(0, 0, s, s); let q = 3; const r = () => (q = (q * 16807) % 2147483647) / 2147483647; for (let i = 0; i < 450; i++) { g.fillStyle = ['#b9b0a3', '#8f877c', '#e8e2d8', '#a89c8c', '#c7bfb2'][i % 5]; g.beginPath(); g.ellipse(r() * s, r() * s, 1 + r() * 3, 1 + r() * 2, r() * 3, 0, 7); g.fill(); } }),
    glow: (() => { const c = document.createElement('canvas'); c.width = c.height = 128; const g = c.getContext('2d'); const gr = g.createRadialGradient(64, 64, 4, 64, 64, 64); gr.addColorStop(0, 'rgba(255,214,150,1)'); gr.addColorStop(.45, 'rgba(255,190,110,.45)'); gr.addColorStop(1, 'rgba(255,170,90,0)'); g.fillStyle = gr; g.fillRect(0, 0, 128, 128); const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t; })()
  };
  return TEX;
}
const floorKind = n => n === '客餐厅' ? ['terr', 2.2] : n === '厨房' || n === '卫生间' ? ['tile', 1.6] : n === '阳台' ? ['deck', 2] : n === '主卧' ? ['oak', 2.4] : ['light', 2.4];

/* ================= the home ================= */
export function createHome(api) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' }); // throws without WebGL
  const pr = () => Math.min(window.devicePixelRatio || 1, 2);
  renderer.setPixelRatio(pr()); renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.05; renderer.setClearColor(0x000000, 0);
  const canvas = renderer.domElement; canvas.className = 'home-canvas';
  const scene = new THREE.Scene();
  const camera = new THREE.OrthographicCamera(-5, 5, 5, -5, .1, 200);
  const hemi = new THREE.HemisphereLight('#fff6ec', '#c9b8a6', 1.2); scene.add(hemi);
  const sun = new THREE.DirectionalLight('#fff3e2', 2.2); sun.position.set(-8, 14, 6); sun.castShadow = true; sun.shadow.mapSize.set(1024, 1024);
  Object.assign(sun.shadow.camera, { left: -10, right: 10, top: 10, bottom: -10, near: 1, far: 40 }); sun.shadow.bias = -.0004; sun.shadow.radius = 3; scene.add(sun);
  const world = new THREE.Group(); scene.add(world);
  const houseG = new THREE.Group(); world.add(houseG);
  const furnG = new THREE.Group(); world.add(furnG);
  const ring = new THREE.Mesh(new RoundedBoxGeometry(1, .02, 1, 2, .08), new THREE.MeshBasicMaterial({ color: '#3fae86', transparent: true, opacity: .5, depthWrite: false })); ring.visible = false; furnG.add(ring);
  const reducedQ = matchMedia('(prefers-reduced-motion: reduce)'), darkQ = matchMedia('(prefers-color-scheme: dark)');
  const reduced = () => reducedQ.matches, isDark = () => darkQ.matches;

  let T = null, planKey = '', RECTS = [], HOUSE = { W: 1, D: 1 }, walls = [], glows = [];
  let view = null, mode = 'normal', host = null, labelsOn = true, overflow = {};
  const items = new Map(); // id -> {id,name,room,item,model,g,x,z,rot,ghost,shown}
  const cam = { az: VIEW.az, pol: VIEW.pol, zoom: VIEW.zoom, tx: 0, tz: 0 };
  let tw = null, drops = [], raf = 0, last = 0, spinOn = false, onScreen = true;

  /* ---------- DOM for the 我的家 tab (persistent; app.js moves it into each render's host) ---------- */
  const root = document.createElement('div'); root.className = 'hv'; root.dataset.mode = 'normal';
  root.innerHTML = `<div class="hv-stage"><div class="hv-slot"></div><div class="hv-labels" aria-hidden="false"></div></div>
  <div class="hv-top"><div class="hv-title"><h1>我的家</h1><p class="hv-sub">单指转动 · 双指缩放</p></div><div class="hv-tools"><button type="button" class="hg gbtn gchip" data-hv="place">${svg(ICON.place)}<span>摆放</span></button><button type="button" class="hg gbtn gchip" data-hv="layout">${svg(ICON.layout)}<span>户型</span></button></div></div>
  <div class="hv-side"><button type="button" class="hg gbtn gcirc" data-hv="labels" aria-pressed="true" aria-label="隐藏房间标签"></button><button type="button" class="hg gbtn gcirc" data-hv="reset" aria-label="回到默认视角">${svg(ICON.reset)}</button></div>
  <div class="hv-dock" data-dock="place" hidden><div class="hg hv-selbar" hidden><button type="button" class="hv-who" data-hv="relook" aria-label="换外观"></button><button type="button" class="hg gbtn gchip" data-hv="rot">${svg(ICON.rot)}<span>转向</span></button><button type="button" class="hg gbtn gchip" data-hv="drop">放下</button></div><div class="hg hv-tray"><h3>还没放进家里的 <span class="hv-tray-n"></span></h3><div class="hv-tray-row"></div></div></div>
  <div class="hv-dock" data-dock="resize" hidden><div class="hg hv-selbar"><div class="hv-who"><b>拖动墙上的把手调整房间</b><span class="hv-area"></span></div><button type="button" class="hg gbtn gchip" data-hv="tpl">换户型</button></div></div>
`;
  // Sheets live on <body> so they cover the tab bar; the tab's own stacking context would keep them under it.
  const layer = document.createElement('div'); layer.className = 'hv-layer';
  layer.innerHTML = `<div class="hv-scrim"></div>
  <div class="hv-sheet hg" data-sheet="tpl" role="dialog" aria-modal="true" aria-label="选户型"><div class="hv-grab"></div><div class="hv-sheet-head"><h2>选户型</h2><button type="button" class="hg gbtn gcirc" data-hv="close" aria-label="关闭">${svg(ICON.close)}</button></div><div class="hv-sheet-body"><p class="hv-hint">选一个最接近的，再拖墙调整每个房间的大小。</p><div class="hv-tpl"></div><button type="button" class="hv-primary gbtn" data-hv="tpl-ok">调整房间大小</button></div></div>
  <div class="hv-sheet hg" data-sheet="pick" role="dialog" aria-modal="true" aria-label="选外观"><div class="hv-grab"></div><div class="hv-sheet-head"><h2>选外观</h2><button type="button" class="hg gbtn gcirc" data-hv="close" aria-label="关闭">${svg(ICON.close)}</button></div><div class="hv-sheet-body"><p class="hv-hint hv-pick-hint">挑一个和你买的东西样子相近的就行。</p><div class="hv-pal-sec"><h3>家电</h3><div class="hv-pal" data-pal="app"></div></div><div class="hv-pal-sec"><h3>家具</h3><div class="hv-pal" data-pal="fur"></div></div><div class="hv-pal-sec"><h3>生活</h3><div class="hv-pal" data-pal="liv"></div></div><button type="button" class="hv-primary gbtn" data-hv="pick-ok" disabled>用这个外观</button></div></div>`;
  const q = s => root.querySelector(s) || layer.querySelector(s), qa = s => [...root.querySelectorAll(s), ...layer.querySelectorAll(s)];
  const slotHome = q('.hv-slot'), labelsEl = q('.hv-labels');

  /* ---------- plan & house ---------- */
  function wallMat() { return M('#f3eee6', { r: .9 }); }
  function glassMat() { return M('#cfe6f2', { t: .28, r: .05, m: .1 }); }
  function disposeHouse() {
    houseG.traverse(o => { if (o.isMesh) { o.geometry.dispose(); if (o.userData.own) { o.material.map?.dispose(); o.material.dispose(); } } });
    houseG.clear(); walls = []; glows = [];
  }
  function rectAt(x, z) { return RECTS.findIndex(({ r }) => x > r[0] && x < r[2] && z > r[1] && z < r[3]); }
  function buildHouse() {
    disposeHouse();
    const { rects, W, D } = HM.makeRects(T); RECTS = rects; HOUSE = { W, D };
    world.position.set(-W / 2, 0, -D / 2);
    const R = Math.max(W, D) * .75 + 1; Object.assign(sun.shadow.camera, { left: -R, right: R, top: R, bottom: -R }); sun.shadow.camera.updateProjectionMatrix();
    const dark = isDark(), tex = textures();
    const slabs = [];
    rects.forEach(({ name, r }) => {
      const w = r[2] - r[0], d = r[3] - r[1];
      slabs.push(new THREE.BoxGeometry(w + WT, .36, d + WT).translate(r[0] + w / 2, -.2, r[1] + d / 2));
      const [k, s] = floorKind(name); const t = tex[k].clone(); t.needsUpdate = true; t.repeat.set(w / s, d / s);
      const f = new THREE.Mesh(new THREE.BoxGeometry(w, .04, d), new THREE.MeshStandardMaterial({ map: t, roughness: .8 })); f.userData.own = true;
      f.position.set(r[0] + w / 2, -.02, r[1] + d / 2); f.receiveShadow = true; houseG.add(f);
      if (name !== '阳台' && w * d > 3) { // night: a warm pool of light on each room's floor instead of a point light
        const gm = new THREE.MeshBasicMaterial({ map: tex.glow, transparent: true, opacity: .7, blending: THREE.AdditiveBlending, depthWrite: false }); gm.userData = {};
        const p = new THREE.Mesh(new THREE.PlaneGeometry(Math.min(w, d) * 1.5, Math.min(w, d) * 1.5), gm); p.userData.own = true;
        p.rotation.x = -PI / 2; p.position.set(r[0] + w / 2, .015, r[1] + d / 2); p.scale.set(w / Math.min(w, d), d / Math.min(w, d), 1); p.visible = dark; p.renderOrder = 2;
        houseG.add(p); glows.push(p);
      }
    });
    const slab = new THREE.Mesh(mergeGeometries(slabs), M(dark ? '#2c3330' : '#e8e1d7')); slabs.forEach(g => g.dispose()); slab.receiveShadow = true; houseG.add(slab);
    const st = .05, eps = .08;
    rects.forEach((Rc, i) => {
      const [x0, z0, x1, z1] = Rc.r;
      [[[x0, z0], [x1, z0], [0, -1]], [[x1, z0], [x1, z1], [1, 0]], [[x0, z1], [x1, z1], [0, 1]], [[x0, z0], [x0, z1], [-1, 0]]].forEach(([a, b, n]) => {
        const len = Math.hypot(b[0] - a[0], b[1] - a[1]), N = Math.max(1, Math.round(len / st)); let run = null;
        const push = r => {
          const o = r.j >= 0 ? rects[r.j] : null; if (o && o.name === Rc.name) return; if (o && r.j < i) return;
          const A = [a[0] + (b[0] - a[0]) * r.t0, a[1] + (b[1] - a[1]) * r.t0], Bp = [a[0] + (b[0] - a[0]) * r.t1, a[1] + (b[1] - a[1]) * r.t1];
          if (Math.hypot(Bp[0] - A[0], Bp[1] - A[1]) < .15) return; addWall(A, Bp, n, Rc.name, o ? o.name : null);
        };
        for (let k = 0; k < N; k++) {
          const t = (k + .5) / N, px = a[0] + (b[0] - a[0]) * t + n[0] * eps, pz = a[1] + (b[1] - a[1]) * t + n[1] * eps; const j = rectAt(px, pz);
          if (!run || run.j !== j) { if (run) push(run); run = { j, t0: k / N, t1: (k + 1) / N }; } else run.t1 = (k + 1) / N;
        }
        push(run);
      });
    });
    buildLabels();
  }
  function addWall(a, b, n, inside, outside) {
    const len = Math.hypot(b[0] - a[0], b[1] - a[1]); const g = new THREE.Group(); g.position.set((a[0] + b[0]) / 2, 0, (a[1] + b[1]) / 2);
    if (Math.abs(b[0] - a[0]) < .01) g.rotation.y = PI / 2;
    const ext = !outside, balc = inside === '阳台' || outside === '阳台'; let ops = [], kind = ext ? 'ext' : 'int';
    const solid = [], panes = [], frames = [];
    if (ext && inside === '阳台') {
      kind = 'rail';
      solid.push(new THREE.BoxGeometry(len + WT, .12, WT).translate(0, .06, 0));
      panes.push(new THREE.BoxGeometry(len, .85, .03).translate(0, .545, 0));
      frames.push(new THREE.BoxGeometry(len + WT, .04, .06).translate(0, .99, 0));
    } else {
      if (balc) ops = [[-len / 2 + .3, len / 2 - .3, 0, 2.2, 1]];
      else if (ext && len > 1.4) { const w = inside === '卫生间' ? .8 : Math.min(len - .8, 2.4); ops = inside === '卫生间' ? [[-w / 2, w / 2, 1.45, 2.1, 1]] : [[-w / 2, w / 2, .85, 2.15, 1]]; }
      else if (!ext && len > 1.3) ops = [[-len / 2 + .25, -len / 2 + 1.15, 0, 2.1, 0]];
      let x = -len / 2 - WT / 2; const end = len / 2 + WT / 2;
      const piece = (x0, x1, y0, y1) => { if (x1 - x0 < .01 || y1 - y0 < .01) return; solid.push(new THREE.BoxGeometry(x1 - x0, y1 - y0, WT).translate((x0 + x1) / 2, (y0 + y1) / 2, 0)); };
      ops.forEach(([o0, o1, y0, y1, gl]) => {
        piece(x, o0, 0, WALL_H); piece(o0, o1, 0, y0); piece(o0, o1, y1, WALL_H);
        if (gl) { panes.push(new THREE.BoxGeometry(o1 - o0, y1 - y0, .02).translate((o0 + o1) / 2, (y0 + y1) / 2, 0)); frames.push(new THREE.BoxGeometry(.03, y1 - y0, .05).translate((o0 + o1) / 2, (y0 + y1) / 2, 0)); }
        x = o1;
      });
      piece(x, end, 0, WALL_H);
    }
    const add = (list, mat, shadow) => { if (!list.length) return; const m = new THREE.Mesh(mergeGeometries(list), mat); list.forEach(l => l.dispose()); m.castShadow = shadow; m.receiveShadow = shadow; g.add(m); };
    add(solid, kind === 'rail' ? M('#e8e1d7') : wallMat(), true);
    add(panes, kind === 'rail' ? M('#cfe6f2', { t: .3, r: .05 }) : glassMat(), false);
    add(frames, M('#3a3f42'), false);
    houseG.add(g); walls.push({ g, kind, n, cur: 1 });
  }
  // Near-side outer walls drop low so the inside is always visible; interior walls stay half height.
  function wallsStep(force) {
    const vx = Math.sin(cam.az), vz = Math.cos(cam.az); let moving = false;
    for (const w of walls) {
      let tgt = 1; if (w.kind === 'int') tgt = .42;
      if (w.kind === 'ext' && (w.n[0] * vx + w.n[1] * vz) > .25) tgt = .07;
      if (mode === 'resize' && view === 'home' && w.kind !== 'rail') tgt = .1;
      const next = force || reduced() ? tgt : w.cur + (tgt - w.cur) * .18;
      if (Math.abs(next - tgt) > .004) moving = true; else if (!force) { w.cur = tgt; w.g.scale.y = tgt; continue; }
      w.cur = next; w.g.scale.y = w.cur;
    }
    return moving;
  }

  /* ---------- items ---------- */
  const qualifies = i => i.category === HM.FURNITURE && i.room !== '全屋';
  function setPlan() {
    const d = api.data();
    const t = HM.planOf(d.project.home, d.project.layout), key = JSON.stringify(HM.sizesOf(t)) + t.key;
    if (key === planKey) return false;
    T = t; planKey = key; buildHouse(); return true;
  }
  function placeEntry(e) {
    const p = HM.placeFromModel(e.model, RECTS, e.room);
    if (!p) { e.placed = false; return; }
    e.placed = true; e.x = p.x; e.z = p.z; e.rot = p.rot;
    e.g.position.set(p.x, 0, p.z); e.g.rotation.y = p.rot * PI / 2;
  }
  function syncItems(force) {
    const d = api.data(), seen = new Set();
    for (const i of d.items) {
      if (!qualifies(i) || !i.model || !HM.KINDS[i.model.kind]) continue;
      seen.add(i.id);
      const ghost = api.paid(i) <= 0;
      let e = items.get(i.id);
      if (e && (e.model.kind !== i.model.kind || e.ghost !== ghost)) { furnG.remove(e.g); e.g = null; }
      if (!e) { e = { id: i.id }; items.set(i.id, e); }
      const moved = !e.model || e.room !== i.room || e.model.x !== i.model.x || e.model.z !== i.model.z || e.model.rot !== i.model.rot;
      Object.assign(e, { name: i.name, room: i.room, item: i, ghost });
      if (!(drag && drag.e === e)) e.model = { ...i.model };
      if (!e.g) { e.g = instance(e.model.kind, ghost); e.g.traverse(o => { o.userData.entry = e; }); furnG.add(e.g); }
      if (moved || force) placeEntry(e);
    }
    for (const [id, e] of items) if (!seen.has(id)) { furnG.remove(e.g); items.delete(id); if (selected === e) select(null); }
    applyFit();
  }
  function applyFit() {
    overflow = {};
    const byRoom = new Map();
    for (const e of items.values()) { if (!byRoom.has(e.room)) byRoom.set(e.room, []); byRoom.get(e.room).push(e); }
    for (const [room, list] of byRoom) {
      const { shown, hidden } = HM.fitRoom(list.map(e => ({ e, kind: e.model.kind })), RECTS, room);
      shown.forEach(s => { s.e.shown = s.e.placed; s.e.g.visible = s.e.placed; });
      hidden.forEach(s => { s.e.shown = false; s.e.g.visible = false; });
      if (hidden.length && HM.bbox(RECTS, room)) overflow[room] = hidden.length;
    }
    if (selected && !selected.shown) select(null);
    paintLabels();
  }

  /* ---------- labels, handles ---------- */
  let labelEls = [], handleEls = [];
  function buildLabels() {
    labelsEl.innerHTML = ''; labelEls = [];
    for (const n of HM.roomNames(RECTS)) {
      const rs = RECTS.filter(r => r.name === n).sort((a, b) => (b.r[2] - b.r[0]) * (b.r[3] - b.r[1]) - (a.r[2] - a.r[0]) * (a.r[3] - a.r[1]))[0].r;
      const el = document.createElement('div'); el.className = 'hv-label hg'; labelsEl.appendChild(el);
      labelEls.push({ el, n, p: new THREE.Vector3((rs[0] + rs[2]) / 2, .1, (rs[1] + rs[3]) / 2) });
    }
    buildHandles(); paintLabels();
  }
  function paintLabels() {
    const resize = mode === 'resize';
    for (const l of labelEls) {
      const extra = overflow[l.n] ? `<span class="hv-more">+${overflow[l.n]}</span>` : '';
      // With labels hidden, a room that holds more than it can draw still shows its +N on its own.
      const moreOnly = !labelsOn && !resize && !!extra;
      l.el.innerHTML = resize ? `${l.n}<small>${HM.area(RECTS, l.n).toFixed(1)}㎡</small>` : moreOnly ? `<span class="hv-more" aria-label="${l.n}还有 ${overflow[l.n]} 件没画出来">+${overflow[l.n]}</span>` : `${l.n}${extra}`;
      l.el.hidden = !labelsOn && !resize && !moreOnly; l.el.classList.toggle('dim', mode === 'place'); l.el.classList.toggle('more-only', moreOnly);
    }
  }
  function buildHandles() {
    const next = mode === 'resize' ? HM.handles(T) : [];
    // While a wall is dragged the plan rebuilds every frame: keep the same handle elements (the touch stays on them).
    if (next.length && next.length === handleEls.length && next.every((h, i) => h.key === handleEls[i].key)) {
      next.forEach((h, i) => { handleEls[i].p = h.p; handleEls[i].v.set(h.p[0], .3, h.p[1]); labelsEl.appendChild(handleEls[i].el); });
      return;
    }
    handleEls.forEach(h => h.el.remove()); handleEls = [];
    for (const h of next) {
      const el = document.createElement('button'); el.type = 'button'; el.className = 'hv-handle hg gbtn'; el.setAttribute('aria-label', '拖动调整房间');
      el.innerHTML = svg(h.ax === 'x' ? ICON.hx : ICON.hz); labelsEl.appendChild(el);
      h.el = el; h.v = new THREE.Vector3(h.p[0], .3, h.p[1]); handleEls.push(h);
      const start = () => { handleDrag = h; el.classList.add('pressed'); };
      el.addEventListener('touchstart', e => { e.preventDefault(); e.stopPropagation(); start(); }, { passive: false });
      el.addEventListener('pointerdown', e => { if (e.pointerType === 'touch') return; e.preventDefault(); e.stopPropagation(); start(); });
      el.addEventListener('mousedown', e => { if (window.PointerEvent) return; e.preventDefault(); start(); });
    }
  }
  const v3 = new THREE.Vector3();
  function project(p) { v3.copy(p); world.localToWorld(v3); v3.project(camera); return [(v3.x + 1) / 2 * canvas.clientWidth, (1 - v3.y) / 2 * canvas.clientHeight]; }
  function updateOverlay() {
    if (view !== 'home') return;
    for (const l of labelEls) { const [x, y] = project(l.p); l.el.style.transform = `translate(${x.toFixed(1)}px,${y.toFixed(1)}px) translate(-50%,-50%)`; }
    for (const h of handleEls) { const [x, y] = project(h.v); h.el.style.transform = `translate(${x.toFixed(1)}px,${y.toFixed(1)}px) translate(-50%,-50%)`; }
  }
  function paintLabelBtn() {
    const b = q('[data-hv="labels"]'); b.setAttribute('aria-pressed', String(labelsOn)); b.setAttribute('aria-label', labelsOn ? '隐藏房间标签' : '显示房间标签');
    b.classList.toggle('on', !labelsOn); b.innerHTML = svg(labelsOn ? ICON.tag : ICON.tagOff);
  }

  /* ---------- camera ---------- */
  function fit() {
    const w = canvas.parentElement?.clientWidth || 0, h = canvas.parentElement?.clientHeight || 0; if (!w || !h) return false;
    renderer.setPixelRatio(pr()); renderer.setSize(w, h, false);
    const a = w / h, S = Math.max(HOUSE.W, HOUSE.D);
    let ww, hh;
    let up = .5; // share of the frustum above the target: more than half sets the house lower, clear of the card caption
    if (view === 'mini') { hh = S * (a > 2 ? .98 : 1.22); ww = hh * a; if (ww < S * 1.6) { ww = S * 1.6; hh = ww / a; } up = .56; }
    else { const F = S * 1.47; if (a < 1) { ww = F; hh = ww / a; } else { hh = S * 1.22; ww = hh * a; } up = a < 1 ? (mode === 'place' ? .42 : mode === 'resize' ? .48 : .54) : .5; }
    Object.assign(camera, { left: -ww / 2, right: ww / 2, top: hh * up, bottom: -hh * (1 - up) });
    return true;
  }
  function placeCamera() {
    const d = 40, s = Math.sin(cam.pol);
    camera.position.set(cam.tx + s * Math.sin(cam.az) * d, Math.cos(cam.pol) * d, cam.tz + s * Math.cos(cam.az) * d);
    camera.zoom = cam.zoom; camera.lookAt(cam.tx, 0, cam.tz); camera.updateProjectionMatrix();
  }
  function tween(to, ms) {
    const from = { ...cam };
    if (!ms || reduced()) { Object.assign(cam, to); tw = null; invalidate(); return; }
    let da = (to.az ?? from.az) - from.az; da = ((da + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI;
    tw = { from, to: { ...from, ...to, az: from.az + da }, t0: performance.now(), ms }; invalidate();
  }
  function stepTween(now) {
    if (!tw) return false;
    const k = Math.min(1, (now - tw.t0) / tw.ms), e = 1 - Math.pow(1 - k, 3);
    for (const key of ['az', 'pol', 'zoom', 'tx', 'tz']) cam[key] = tw.from[key] + (tw.to[key] - tw.from[key]) * e;
    if (k >= 1) tw = null; return true;
  }
  const resetView = anim => tween({ ...VIEW, tx: 0, tz: 0 }, anim ? 650 : 0);
  function focusRoom(name, zoom = 1.6) {
    const bb = HM.bbox(RECTS, name); if (!bb) return;
    tween({ tx: (bb.x0 + bb.x1) / 2 - HOUSE.W / 2, tz: (bb.z0 + bb.z1) / 2 - HOUSE.D / 2, zoom }, 700);
  }
  function stepDrops(now) {
    drops = drops.filter(d => { d.e.g.visible = now >= d.t0; const k = Math.max(0, Math.min(1, (now - d.t0) / 750)); const b = k < 1 ? Math.abs(Math.cos(k * PI * 1.5)) * (1 - k) * 1.6 : 0; d.e.g.position.y = b; d.e.g.scale.setScalar(.6 + .4 * (k < .15 ? k / .15 : 1)); return k < 1; });
    return drops.length > 0;
  }

  /* ---------- render on demand ---------- */
  function invalidate() { if (!raf && view) raf = requestAnimationFrame(frame); }
  function frame(now) {
    raf = 0; if (!view) return;
    const dt = last ? Math.min(.05, (now - last) / 1000) : .016; last = now;
    let more = false;
    if (view === 'mini' && spinOn && onScreen && !document.hidden && !reduced()) { cam.az += dt * .18; more = true; }
    if (stepTween(now)) more = true;
    if (stepDrops(now)) more = true;
    if (wallsStep(false)) more = true;
    if (inertia && view === 'home' && !gesture) { const k = dt * 60, decay = Math.pow(.9, k); cam.az += inertia.az * k; cam.pol = Math.min(POL_MAX, Math.max(POL_MIN, cam.pol + inertia.pol * k)); inertia.az *= decay; inertia.pol *= decay; if (Math.abs(inertia.az) + Math.abs(inertia.pol) < .0005) inertia = null; else more = true; }
    placeCamera(); renderer.render(scene, camera); updateOverlay();
    if (more || gesture) raf = requestAnimationFrame(frame); else last = 0;
  }

  /* ---------- gestures: touch events first (the phone WebView has no pointer events), pointer/mouse for desktop ---------- */
  const ray = new THREE.Raycaster(), ndc = new THREE.Vector2(), floor = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
  function aim(cx, cy) { const r = canvas.getBoundingClientRect(); ndc.set((cx - r.left) / r.width * 2 - 1, -(cy - r.top) / r.height * 2 + 1); ray.setFromCamera(ndc, camera); }
  function floorHit(cx, cy) { aim(cx, cy); const p = new THREE.Vector3(); if (!ray.ray.intersectPlane(floor, p)) return null; return world.worldToLocal(p); }
  function itemAt(cx, cy) {
    aim(cx, cy);
    const hits = ray.intersectObjects([...items.values()].filter(e => e.g.visible).map(e => e.g), true);
    return hits.length ? hits[0].object.userData.entry || null : null;
  }
  let gesture = null, drag = null, handleDrag = null, inertia = null, selected = null;
  function beginOne(x, y) {
    inertia = null;
    if (mode === 'place') {
      const e = itemAt(x, y);
      if (e) { select(e); const p = floorHit(x, y); if (p) { drag = { e, off: [e.x - p.x, e.z - p.z], moved: false, last: [e.x, e.z] }; e.g.position.y = .08; } gesture = { kind: 'drag', x, y, t: performance.now() }; invalidate(); return; }
    }
    gesture = { kind: 'rotate', x, y, lx: x, ly: y, t: performance.now(), moved: false };
  }
  function moveOne(x, y, ts) {
    if (handleDrag) { const p = floorHit(x, y); if (p) applyHandle(handleDrag, p); return; }
    if (!gesture) return;
    if (gesture.kind === 'drag' && drag) {
      const p = floorHit(x, y); if (!p) return;
      if (Math.hypot(x - gesture.x, y - gesture.y) > 4) drag.moved = true;
      if (!drag.moved) return; // a tap that wobbles a few pixels never moves it unsaved
      const bb = HM.bbox(RECTS, drag.e.room); if (!bb) return;
      const tx = p.x + drag.off[0], tz = p.z + drag.off[1];
      // Heading of the drag over the last ~5 cm: the wall it is pushed towards is the one it may back onto.
      if (!drag.last) drag.last = [tx, tz]; const mv = { dx: tx - drag.last[0], dz: tz - drag.last[1] }; if (Math.hypot(mv.dx, mv.dz) > .05) drag.last = [tx, tz];
      const r = HM.clampPlacement(HM.KINDS[drag.e.model.kind], bb, tx, tz, drag.e.rot, mv);
      Object.assign(drag.e, { x: r.x, z: r.z, rot: r.rot }); drag.e.model = { ...drag.e.model, x: +r.fx.toFixed(4), z: +r.fz.toFixed(4), rot: r.rot };
      drag.e.g.position.set(r.x, .08, r.z); drag.e.g.rotation.y = r.rot * PI / 2; updateRing(); invalidate(); return;
    }
    if (gesture.kind === 'rotate') {
      const h = canvas.clientHeight || 1, dx = x - gesture.lx, dy = y - gesture.ly;
      if (Math.hypot(x - gesture.x, y - gesture.y) > 6) gesture.moved = true;
      const da = -2 * PI * dx / h * .8, dp = -2 * PI * dy / h * .8;
      cam.az += da; cam.pol = Math.min(POL_MAX, Math.max(POL_MIN, cam.pol + dp)); gesture.lx = x; gesture.ly = y; { const now = ts || performance.now(), f = 16.667 / Math.max(8, now - (gesture.lt || gesture.t)); gesture.lt = now; gesture.v = { az: da * f, pol: dp * f }; } // per 60 Hz frame, from event time stamps
      tw = null; invalidate();
    }
  }
  function endOne() {
    if (handleDrag) { handleDrag.el.classList.remove('pressed'); handleDrag = null; saveHome(); }
    if (gesture?.kind === 'rotate') {
      if (!gesture.moved && mode === 'place') select(null);
      else if (gesture.v && !reduced() && performance.now() - gesture.t > 60) inertia = { ...gesture.v };
    }
    if (drag) endDrag();
    gesture = null; invalidate();
  }
  let pinch = null;
  const dist = t => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
  canvas.addEventListener('touchstart', e => {
    if (view !== 'home') return;
    e.preventDefault();
    if (e.touches.length >= 2) {
      if (drag) endDrag();
      gesture = null; pinch = { d: dist(e.touches), zoom: cam.zoom }; tw = null; return;
    }
    beginOne(e.touches[0].clientX, e.touches[0].clientY);
  }, { passive: false });
  canvas.addEventListener('touchmove', e => {
    if (view !== 'home') return; e.preventDefault();
    if (pinch && e.touches.length >= 2) { cam.zoom = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, pinch.zoom * dist(e.touches) / Math.max(1, pinch.d))); invalidate(); return; }
    if (e.touches.length === 1) moveOne(e.touches[0].clientX, e.touches[0].clientY, e.timeStamp);
  }, { passive: false });
  const touchEnd = e => {
    if (view !== 'home') return;
    if (pinch) { if (e.touches.length < 2) { pinch = null; if (e.touches.length === 1) gesture = { kind: 'rotate', x: e.touches[0].clientX, y: e.touches[0].clientY, lx: e.touches[0].clientX, ly: e.touches[0].clientY, t: performance.now(), moved: true }; else endOne(); } return; }
    if (!e.touches.length) endOne();
  };
  canvas.addEventListener('touchend', touchEnd); canvas.addEventListener('touchcancel', touchEnd);
  // Handles live in the label layer; their drags follow the finger anywhere on the page.
  document.addEventListener('touchmove', e => { if (handleDrag && e.touches.length) { e.preventDefault(); moveOne(e.touches[0].clientX, e.touches[0].clientY); } }, { passive: false });
  document.addEventListener('touchend', e => { if (handleDrag && !e.touches.length) endOne(); });
  document.addEventListener('touchcancel', () => { if (handleDrag) endOne(); });
  if (window.PointerEvent) {
    canvas.addEventListener('pointerdown', e => {
      if (view !== 'home' || e.pointerType === 'touch' || e.button !== 0) return;
      e.preventDefault(); beginOne(e.clientX, e.clientY);
      const mv = ev => moveOne(ev.clientX, ev.clientY), up = () => { removeEventListener('pointermove', mv); removeEventListener('pointerup', up); removeEventListener('pointercancel', up); endOne(); };
      addEventListener('pointermove', mv); addEventListener('pointerup', up); addEventListener('pointercancel', up);
    });
    addEventListener('pointermove', e => { if (handleDrag && e.pointerType !== 'touch') moveOne(e.clientX, e.clientY); });
    addEventListener('pointerup', e => { if (handleDrag && e.pointerType !== 'touch') endOne(); });
  } else {
    canvas.addEventListener('mousedown', e => { if (view !== 'home') return; e.preventDefault(); beginOne(e.clientX, e.clientY); });
    addEventListener('mousemove', e => { if (gesture || handleDrag) moveOne(e.clientX, e.clientY); });
    addEventListener('mouseup', () => { if (gesture || handleDrag) endOne(); });
  }
  canvas.addEventListener('wheel', e => { if (view !== 'home') return; e.preventDefault(); cam.zoom = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, cam.zoom * Math.pow(.9985, e.deltaY))); tw = null; invalidate(); }, { passive: false });
  canvas.addEventListener('webglcontextlost', e => e.preventDefault());
  canvas.addEventListener('webglcontextrestored', () => invalidate());

  /* ---------- selection, saving ---------- */
  function updateRing() {
    const e = selected; if (!e || !e.shown) { ring.visible = false; return; }
    const [w, d] = HM.footprint(HM.KINDS[e.model.kind], e.rot);
    ring.visible = true; ring.scale.set(w + .2, 1, d + .2); ring.position.set(e.x, .012, e.z);
  }
  function itemLine(i) {
    const paid = api.paid(i);
    return paid > 0 ? `已付 ${api.money(paid)}` : i.costMode !== 'actual' && i.price !== null ? `预计 ${api.money(i.price)} · 未付款` : i.budget !== null ? `预算 ${api.money(i.budget)} · 未付款` : '未付款';
  }
  function select(e) {
    selected = e; const bar = q('.hv-selbar'); bar.hidden = !e;
    if (e) q('.hv-who[data-hv="relook"]').innerHTML = `<b>${api.esc(e.name)}</b><span>${api.esc(itemLine(e.item))} · ${api.esc(e.room)}${HM.KINDS[e.model.kind].wall ? ' · 贴墙摆放' : ''}</span>`;
    updateRing(); invalidate();
  }
  // A failed save puts the piece back where the ledger has it, so the screen never shows a spot that will not survive a restart.
  const saveGen = new Map();
  function saveModel(e) { const gen = (saveGen.get(e.id) || 0) + 1; saveGen.set(e.id, gen); api.saveModel(e.id, { ...e.model }).then(ok => { if (ok || saveGen.get(e.id) !== gen) return; if (drag && drag.e === e) { e.rollback = true; return; } api.toast('没保存上，已放回原位，请再试一次', null, true); rollback(e); }); }
  // Back to the ledger's spot for this one piece; the others keep what they saved.
  function rollback(e) { e.rollback = false; const i = api.data().items.find(x => x.id === e.id); if (i?.model) { e.model = { ...i.model }; placeEntry(e); applyFit(); } updateRing(); invalidate(); }
  // A failure that lands mid-drag waits for the finger: a real move saves anew, a mere touch rolls back.
  function endDrag() { const d = drag; drag = null; d.e.g.position.y = 0; if (d.moved) { d.e.rollback = false; saveModel(d.e); } else if (d.e.rollback) { api.toast('没保存上，已放回原位，请再试一次', null, true); rollback(d.e); } }
  let homeTimer = 0;
  function saveHome() { clearTimeout(homeTimer); homeTimer = setTimeout(() => api.saveHome({ template: T.key, sizes: HM.sizesOf(T) }).then(ok => { if (!ok) api.toast('户型没保存上，下次打开会回到上次保存的样子，请再调整一次', null, true); }), 300); }

  /* ---------- modes, tray, sheets ---------- */
  function setMode(m) {
    if (mode === 'resize' && m !== 'resize') saveHome();
    mode = m; root.dataset.mode = m;
    q('[data-dock="place"]').hidden = m !== 'place'; q('[data-dock="resize"]').hidden = m !== 'resize';
    const pb = q('[data-hv="place"]'), lb = q('[data-hv="layout"]');
    pb.classList.toggle('on', m === 'place'); lb.classList.toggle('on', m === 'resize');
    pb.lastChild.textContent = m === 'place' ? '完成' : '摆放'; lb.lastChild.textContent = m === 'resize' ? '完成' : '户型';
    q('.hv-sub').textContent = m === 'place' ? '点选家具，按住拖动' : m === 'resize' ? '拖动把手调整房间' : '单指转动 · 双指缩放';
    if (m !== 'place') select(null);
    buildHandles(); paintLabels(); renderTray(); paintArea(); fit();
    if (m === 'resize') tween({ az: PI / 4, pol: .45, zoom: .95, tx: 0, tz: 0 }, 600);
    else if (view === 'home') resetView(true);
    invalidate();
  }
  function paintArea() { if (mode === 'resize') q('.hv-area').textContent = `套内约 ${RECTS.reduce((s, { r }) => s + (r[2] - r[0]) * (r[3] - r[1]), 0).toFixed(0)}㎡ · 外墙不动`; }
  function trayItems() { const d = api.data(); return d.items.filter(i => qualifies(i) && !(i.model && HM.KINDS[i.model.kind])); }
  function renderTray() {
    const list = trayItems(), rooms = new Set(HM.roomNames(RECTS));
    q('.hv-tray-n').textContent = list.length ? `· ${list.length}` : '';
    q('.hv-tray').hidden = !list.length;
    q('.hv-tray-row').innerHTML = list.map(i => `<button type="button" class="hg gbtn gchip${rooms.has(i.room) ? '' : ' off'}" data-tray="${api.esc(i.id)}">${api.esc(i.name)} <small>${api.esc(itemLine(i).replace(' · 未付款', ''))} · ${api.esc(i.room)}</small></button>`).join('');
  }
  let openSheets = [];
  function openSheet(name) { const el = q(`[data-sheet="${name}"]`); if (!openSheets.includes(el)) openSheets.push(el); el.classList.add('on'); q('.hv-scrim').classList.add('on'); el.querySelector('[data-hv="close"]').focus({ preventScroll: true }); }
  function closeSheet(el) { el = el || openSheets[openSheets.length - 1]; if (!el) return; el.classList.remove('on'); openSheets = openSheets.filter(x => x !== el); if (!openSheets.length) q('.hv-scrim').classList.remove('on'); if (lent && el.dataset.sheet === 'pick') giveBack(); }
  // 1.5.0 · the ledger form is a modal <dialog>, so the picker borrows a dialog of its own to sit above it;
  // the sheets go back to <body> once it closes.
  let lent = null;
  function giveBack() {
    const l = lent; lent = null;
    setTimeout(() => { document.body.appendChild(layer); if (l.dialog.open) l.dialog.close(); l.onClose && l.onClose(); }, reduced() ? 0 : 320);
  }
  function lendPicker(dialog, opts) {
    if (lent) return;
    lent = { dialog, onClose: opts.onClose };
    dialog.appendChild(layer); if (!dialog.open) dialog.showModal();
    q('[data-sheet="pick"]').getBoundingClientRect(); // a frame at rest, so the sheet slides up
    openPicker(opts);
  }
  const closeAll = () => [...openSheets].forEach(closeSheet);
  let pick = { sel: null, cb: null };
  function openPicker(opts) {
    pick = { sel: opts.sel || null, cb: opts.cb };
    q('.hv-pick-hint').textContent = opts.hint || '挑一个和你买的东西样子相近的就行。';
    q('[data-hv="pick-ok"]').disabled = !pick.sel; fillPalette(); openSheet('pick');
  }
  function fillPalette() {
    const th = thumbs();
    for (const k of ['app', 'fur', 'liv']) q(`[data-pal="${k}"]`).innerHTML = HM.ORDER[k].map((m, n) => `<button type="button" class="hv-tile${pick.sel === m ? ' sel' : ''}" data-look="${m}" aria-label="${HM.LABELS[m] || `${{ app: '家电', fur: '家具', liv: '生活' }[k]}外观 ${n + 1}`}" aria-pressed="${pick.sel === m}"><img alt="" src="${th[m]}"></button>`).join('');
  }
  function renderTpl() {
    q('.hv-tpl').innerHTML = HM.TEMPLATE_ORDER.map(k => {
      const t = HM.planOf({ template: k }), { rects } = HM.makeRects(t), a = rects.reduce((s, { r }) => s + (r[2] - r[0]) * (r[3] - r[1]), 0);
      return `<button type="button" data-tplk="${k}" class="${k === T.key ? 'sel' : ''}" aria-pressed="${k === T.key}">${HM.planSvg(t)}<b>${t.n}</b><small>约 ${a.toFixed(0)}㎡</small></button>`;
    }).join('');
  }
  function applyHandle(h, p) {
    const next = HM.moveHandle(T, h.key, p.x, p.z);
    if (JSON.stringify(next) === JSON.stringify(T)) return;
    T = HM.planOf({ template: T.key, sizes: HM.sizesOf(next) }); planKey = JSON.stringify(HM.sizesOf(T)) + T.key;
    if (!rebuildQ) { rebuildQ = true; requestAnimationFrame(() => { rebuildQ = false; rebuildAll(); }); }
  }
  let rebuildQ = false;
  function rebuildAll() { buildHouse(); for (const e of items.values()) placeEntry(e); applyFit(); updateRing(); wallsStep(true); paintArea(); renderTray(); invalidate(); }
  function placeFromTray(id, kind) {
    const i = api.data().items.find(x => x.id === id); if (!i) return;
    if (!HM.bbox(RECTS, i.room)) { api.toast(`户型里没有「${i.room}」，换个户型或改这笔帐的空间`); return; }
    const others = [...items.values()].filter(e => e.room === i.room && e.shown).map(e => { const [w, d] = HM.footprint(HM.KINDS[e.model.kind], e.rot); return { x: e.x, z: e.z, w, d }; });
    const model = HM.findSpot(kind, RECTS, i.room, others);
    api.saveModel(i.id, model).then(ok => {
      if (!ok) { api.toast('没放进去，请再试一次', null, true); return; }
      syncItems(); renderTray();
      const e = items.get(i.id);
      if (e && e.shown) { focusRoom(i.room, 1.5); if (!reduced()) drops.push({ e, t0: performance.now() + 300 }); select(e); }
      else api.toast(`「${i.room}」放满了，这件计入房间标签的 +N`);
      invalidate();
    });
  }
  // Glass press glow on buttons, for touch and mouse alike.
  const press = (b, x, y) => { const r = b.getBoundingClientRect(); b.style.setProperty('--px', ((x - r.left) / r.width * 100) + '%'); b.style.setProperty('--py', ((y - r.top) / r.height * 100) + '%'); b.classList.add('pressed'); setTimeout(() => b.classList.remove('pressed'), 260); };
  root.addEventListener('touchstart', e => { const b = e.target.closest('.gbtn'); if (b && e.touches[0]) press(b, e.touches[0].clientX, e.touches[0].clientY); }, { passive: true });
  root.addEventListener('mousedown', e => { const b = e.target.closest('.gbtn'); if (b) press(b, e.clientX, e.clientY); });
  const onClick = e => {
    const tray = e.target.closest('[data-tray]');
    if (tray) { const id = tray.dataset.tray, i = api.data().items.find(x => x.id === id); if (!i) return; if (tray.classList.contains('off')) { api.toast(`户型里没有「${i.room}」，换个户型或改这笔帐的空间`); return; } openPicker({ cb: kind => placeFromTray(id, kind), hint: `给「${i.name}」挑一个样子相近的，放进${i.room}。` }); return; }
    const tile = e.target.closest('[data-look]');
    if (tile) { pick.sel = tile.dataset.look; qa('.hv-tile').forEach(t => { t.classList.toggle('sel', t === tile); t.setAttribute('aria-pressed', String(t === tile)); }); q('[data-hv="pick-ok"]').disabled = false; return; }
    const tk = e.target.closest('[data-tplk]');
    if (tk) { if (tk.dataset.tplk !== T.key) { T = HM.planOf({ template: tk.dataset.tplk }); planKey = JSON.stringify(HM.sizesOf(T)) + T.key; rebuildAll(); saveHome(); renderTpl(); } return; }
    const b = e.target.closest('[data-hv]'); if (!b || b.disabled) return;
    const a = b.dataset.hv;
    if (a === 'place') setMode(mode === 'place' ? 'normal' : 'place');
    else if (a === 'layout') { if (mode === 'resize') setMode('normal'); else { renderTpl(); openSheet('tpl'); } }
    else if (a === 'tpl') { renderTpl(); openSheet('tpl'); }
    else if (a === 'tpl-ok') { closeSheet(); setMode('resize'); }
    else if (a === 'reset') resetView(true);
    else if (a === 'labels') { labelsOn = !labelsOn; paintLabelBtn(); paintLabels(); api.saveUi({ labels: labelsOn }); api.toast(labelsOn ? '已显示房间标签' : '已隐藏房间标签'); }
    else if (a === 'close') closeSheet(b.closest('.hv-sheet'));
    else if (a === 'pick-ok') { if (!pick.sel) return; const cb = pick.cb; const sel = pick.sel; closeSheet(q('[data-sheet="pick"]')); cb && cb(sel); }
    else if (a === 'rot') { const e2 = selected; if (!e2) return; if (HM.KINDS[e2.model.kind].wall) { api.toast('挂墙的东西会自动朝向屋内', null, 'info'); return; } const r = HM.clampPlacement(HM.KINDS[e2.model.kind], HM.bbox(RECTS, e2.room), e2.x, e2.z, (e2.rot + 1) % 4); e2.model = { ...e2.model, x: +r.fx.toFixed(4), z: +r.fz.toFixed(4), rot: r.rot }; placeEntry(e2); updateRing(); saveModel(e2); invalidate(); }
    else if (a === 'drop') select(null);
    else if (a === 'relook') { const e2 = selected; if (!e2) return; openPicker({ sel: e2.model.kind, hint: `给「${e2.name}」换一个外观。`, cb: kind => { if (kind === e2.model.kind) return; api.saveModel(e2.id, { ...e2.model, kind }).then(ok => { if (!ok) api.toast('外观没换成，请再试一次', null, true); if (ok) { syncItems(); const n = items.get(e2.id); if (n?.shown) select(n); else select(null); } }); } }); }
  };
  root.addEventListener('click', onClick); layer.addEventListener('click', onClick);
  layer.addEventListener('touchstart', e => { const b = e.target.closest('.gbtn'); if (b && e.touches[0]) press(b, e.touches[0].clientX, e.touches[0].clientY); }, { passive: true });
  q('.hv-scrim').addEventListener('click', () => closeSheet());
  layer.addEventListener('keydown', e => { if (e.key === 'Escape' && openSheets.length) { e.stopPropagation(); closeSheet(); } });

  /* ---------- thumbnails: each look rendered once with the shared renderer, cached as a data URL ---------- */
  let THUMB = null, thumbDark = null;
  function thumbs() {
    if (THUMB && thumbDark === isDark()) return THUMB;
    const d = isDark(), ts = new THREE.Scene(); thumbDark = d; THUMB = {};
    ts.add(new THREE.HemisphereLight(d ? '#cfd8e0' : '#fff6ec', d ? '#3a3530' : '#c9b8a6', d ? 1.0 : 1.25));
    const dl = new THREE.DirectionalLight('#fff3e2', d ? 1.6 : 2.2); dl.position.set(-4, 8, 5); ts.add(dl);
    const tc = new THREE.OrthographicCamera(-1, 1, 1, -1, .1, 100);
    const size = new THREE.Vector2(); renderer.getSize(size); const ratio = renderer.getPixelRatio(), shadows = renderer.shadowMap.autoUpdate;
    const out = document.createElement('canvas'); out.width = out.height = 200; const ctx = out.getContext('2d');
    renderer.setPixelRatio(1); renderer.setSize(200, 200, false); renderer.shadowMap.autoUpdate = false;
    for (const k of Object.keys(BUILD)) {
      const g = instance(k, false); ts.add(g);
      const box = new THREE.Box3().setFromObject(g), c = box.getCenter(new THREE.Vector3()), s = box.getSize(new THREE.Vector3());
      const r = Math.max(s.x, s.y, s.z) * .78; Object.assign(tc, { left: -r, right: r, top: r, bottom: -r }); tc.position.set(c.x + 10, c.y + 7.5, c.z + 10); tc.lookAt(c); tc.updateProjectionMatrix();
      renderer.render(ts, tc); ctx.clearRect(0, 0, 200, 200); ctx.drawImage(canvas, 0, 0, 200, 200); THUMB[k] = out.toDataURL('image/png');
      ts.remove(g);
    }
    renderer.shadowMap.autoUpdate = shadows; renderer.setPixelRatio(ratio); renderer.setSize(size.x, size.y, false); invalidate();
    return THUMB;
  }

  /* ---------- theme ---------- */
  function applyTheme() {
    const d = isDark();
    hemi.color.set(d ? '#a9b8c8' : '#fff6ec'); hemi.groundColor.set(d ? '#3a2e24' : '#c9b8a6'); hemi.intensity = d ? .75 : 1.2;
    sun.color.set(d ? '#9db8e6' : '#fff3e2'); sun.intensity = d ? .65 : 2.2;
    wallMat().color.set(d ? '#cfc8bd' : '#f3eee6');
    const gm = glassMat(); gm.emissive.set(d ? '#ffbf6a' : '#000000'); gm.emissiveIntensity = d ? .9 : 0; gm.opacity = d ? .7 : .28;
    renderer.toneMappingExposure = d ? 1.15 : 1.05;
    if (T) { planKey = ''; setPlan(); syncItems(true); wallsStep(true); }
    if (openSheets.some(s => s.dataset.sheet === 'pick')) fillPalette();
    invalidate();
  }
  darkQ.addEventListener?.('change', applyTheme);
  reducedQ.addEventListener?.('change', () => { spinOn = view === 'mini' && !reduced(); invalidate(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) invalidate(); });
  const resizeObs = new ResizeObserver(() => { if (view && fit()) { wallsStep(true); invalidate(); } });
  const io = 'IntersectionObserver' in window ? new IntersectionObserver(es => { for (const en of es) onScreen = en.isIntersecting; if (onScreen) invalidate(); }) : null;

  /* ---------- public ---------- */
  function sync() { if (!T) return; const changed = setPlan(); syncItems(changed); if (view === 'home') { renderTray(); if (selected) select(items.get(selected.id) || null); } invalidate(); }
  function mount(target, kind) {
    const was = view; if (host) { resizeObs.unobserve(host); io?.unobserve(host); }
    view = kind; host = target;
    if (!T) { applyTheme(); setPlan(); }
    if (!layer.isConnected) document.body.appendChild(layer);
    if (kind === 'home') { target.appendChild(root); slotHome.appendChild(canvas); if (was !== 'home') { closeAll(); setMode('normal'); resetView(false); wallsStep(true); } }
    else { target.appendChild(canvas); closeAll(); if (mode !== 'normal') { mode = 'normal'; root.dataset.mode = 'normal'; select(null); } cam.pol = VIEW.pol; cam.zoom = 1; cam.tx = cam.tz = 0; tw = null; inertia = null; }
    spinOn = kind === 'mini' && !reduced();
    resizeObs.observe(target); if (kind === 'mini') { onScreen = true; io?.observe(target); }
    sync(); fit(); wallsStep(true); last = 0; invalidate();
  }
  function hide() { closeAll(); view = null; if (raf) cancelAnimationFrame(raf); raf = 0; gesture = drag = handleDrag = pinch = null; if (host) { resizeObs.unobserve(host); io?.unobserve(host); } host = null; }
  // 1.5.0 · after a look is recorded: fly to its room and drop it in (instant with reduced motion).
  function reveal(id) {
    if (view !== 'home') return false;
    sync(); const e = items.get(id); if (!e || !e.shown) return false;
    focusRoom(e.room, 1.8);
    if (!reduced()) drops.push({ e, t0: performance.now() + 420 });
    invalidate(); return true;
  }
  function arrange(id) {
    if (view !== 'home') return;
    setMode('place'); const e = items.get(id);
    if (e && e.shown) { select(e); focusRoom(e.room, 1.45); }
  }
  // System Back on 我的家: closes a sheet, then drops the selection, then leaves place/resize mode. False when nothing is open.
  function back() {
    if (openSheets.length) { closeSheet(); return true; }
    if (selected) { select(null); return true; }
    if (mode !== 'normal') { setMode('normal'); return true; }
    return false;
  }
  // System Back while the look picker is lent to the ledger form: close only that sheet, never the selection or mode.
  function closeTopSheet() { if (!openSheets.length) return false; closeSheet(); return true; }
  function setUi(ui) { if (ui && typeof ui.labels === 'boolean') { labelsOn = ui.labels; paintLabelBtn(); paintLabels(); } }
  paintLabelBtn();
  // Test hooks for the screenshot script: none of these change the ledger.
  const debug = { cam, items, select: id => select(items.get(id) || null), screen: (id, h = .3) => { const e = items.get(id); if (!e) return null; const r = canvas.getBoundingClientRect(); const [x, y] = project(new THREE.Vector3(e.x, h, e.z)); return [r.left + x, r.top + y]; }, mode: () => mode, setMode };
  return { mount, hide, sync, setUi, openPicker, lendPicker, reveal, arrange, thumbs, back, closeTopSheet, debug };
}
