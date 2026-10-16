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
  check: '<path d="m5 12 4 4L19 6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  trash: '<path d="M5 7h14M10 11v6M14 11v6M7 7l1 12h8l1-12M9 7V4h6v3"/>'
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
// By room type (1.9.0: names count on, 次卧2, 公卫2…).
const floorKind = t => t === '客餐厅' || t === '客厅' || t === '餐厅' ? ['terr', 2.2] : t === '厨房' || t === '卫生间' ? ['tile', 1.6] : t === '阳台' ? ['deck', 2] : t === '主卧' ? ['oak', 2.4] : ['light', 2.4];

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
  <div class="hv-dock" data-dock="place" hidden><div class="hg hv-selbar" hidden><button type="button" class="hv-who" data-hv="relook" aria-label="换外观"></button><button type="button" class="hg gbtn gchip" data-hv="rot">${svg(ICON.rot)}<span>转向</span></button><button type="button" class="hg gbtn gcirc hv-take" data-hv="take" aria-label="拿走这件，帐还在">${svg(ICON.trash)}</button><button type="button" class="hg gbtn gchip" data-hv="drop">放下</button></div><div class="hg hv-tray"><h3>还没放进家里的 <span class="hv-tray-n"></span></h3><div class="hv-tray-row"></div></div></div>
`;
  // Sheets live on <body> so they cover the tab bar; the tab's own stacking context would keep them under it.
  const layer = document.createElement('div'); layer.className = 'hv-layer';
  layer.innerHTML = `<div class="hv-scrim"></div>
  <div class="hv-sheet hg" data-sheet="tpl" role="dialog" aria-modal="true" aria-label="选户型"><div class="hv-grab"></div><div class="hv-sheet-head"><h2>选户型</h2><button type="button" class="hg gbtn gcirc" data-hv="close" aria-label="关闭">${svg(ICON.close)}</button></div><div class="hv-sheet-body"><p class="hv-hint">选一个最接近的当起点，再拖方块调整，按「完成」才保存。</p><div class="hv-tpl"></div></div></div>
  <div class="hv-sheet hg" data-sheet="room" role="dialog" aria-modal="true" aria-label="选房间"><div class="hv-grab"></div><div class="hv-sheet-head"><h2>放到哪个房间</h2><button type="button" class="hg gbtn gcirc" data-hv="close" aria-label="关闭">${svg(ICON.close)}</button></div><div class="hv-sheet-body"><p class="hv-hint hv-room-hint"></p><div class="pe-types hv-rooms"></div></div></div>
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
    rects.forEach(({ type, r }) => {
      const w = r[2] - r[0], d = r[3] - r[1];
      slabs.push(new THREE.BoxGeometry(w + WT, .36, d + WT).translate(r[0] + w / 2, -.2, r[1] + d / 2));
      const [k, s] = floorKind(type); const t = tex[k].clone(); t.needsUpdate = true; t.repeat.set(w / s, d / s);
      const f = new THREE.Mesh(new THREE.BoxGeometry(w, .04, d), new THREE.MeshStandardMaterial({ map: t, roughness: .8 })); f.userData.own = true;
      f.position.set(r[0] + w / 2, -.02, r[1] + d / 2); f.receiveShadow = true; houseG.add(f);
      if (type !== '阳台' && w * d > 3) { // night: a warm pool of light on each room's floor instead of a point light
        const gm = new THREE.MeshBasicMaterial({ map: tex.glow, transparent: true, opacity: .7, blending: THREE.AdditiveBlending, depthWrite: false }); gm.userData = {};
        const p = new THREE.Mesh(new THREE.PlaneGeometry(Math.min(w, d) * 1.5, Math.min(w, d) * 1.5), gm); p.userData.own = true;
        p.rotation.x = -PI / 2; p.position.set(r[0] + w / 2, .015, r[1] + d / 2); p.scale.set(w / Math.min(w, d), d / Math.min(w, d), 1); p.visible = dark; p.renderOrder = 2;
        houseG.add(p); glows.push(p);
      }
    });
    const slab = new THREE.Mesh(mergeGeometries(slabs), M(dark ? '#2c3330' : '#e8e1d7')); slabs.forEach(g => g.dispose()); slab.receiveShadow = true; houseG.add(slab);
    const st = .05, eps = .08, segs = [];
    rects.forEach((Rc, i) => {
      const [x0, z0, x1, z1] = Rc.r;
      [[[x0, z0], [x1, z0], [0, -1]], [[x1, z0], [x1, z1], [1, 0]], [[x0, z1], [x1, z1], [0, 1]], [[x0, z0], [x0, z1], [-1, 0]]].forEach(([a, b, n]) => {
        const len = Math.hypot(b[0] - a[0], b[1] - a[1]), N = Math.max(1, Math.round(len / st)); let run = null;
        const push = r => {
          const o = r.j >= 0 ? rects[r.j] : null; if (o && o.name === Rc.name) return; if (o && r.j < i) return;
          const A = [a[0] + (b[0] - a[0]) * r.t0, a[1] + (b[1] - a[1]) * r.t0], Bp = [a[0] + (b[0] - a[0]) * r.t1, a[1] + (b[1] - a[1]) * r.t1];
          if (Math.hypot(Bp[0] - A[0], Bp[1] - A[1]) < .15) return; segs.push({ A, Bp, n, a: Rc, o });
        };
        for (let k = 0; k < N; k++) {
          const t = (k + .5) / N, px = a[0] + (b[0] - a[0]) * t + n[0] * eps, pz = a[1] + (b[1] - a[1]) * t + n[1] * eps; const j = rectAt(px, pz);
          if (!run || run.j !== j) { if (run) push(run); run = { j, t0: k / N, t1: (k + 1) / N }; } else run.t1 = (k + 1) / N;
        }
        push(run);
      });
    });
    // A bathroom gets one door: inside a room, on its longest wall shared with that room; standing alone, on its
    // longest wall shared with any room. No other wall of it has a door.
    const len = q => Math.hypot(q.Bp[0] - q.A[0], q.Bp[1] - q.A[1]), bathDoor = new Map();
    for (const q of segs) {
      if (!q.o) continue; const [b, h] = q.a.type === '卫生间' ? [q.a, q.o] : q.o.type === '卫生间' ? [q.o, q.a] : [];
      if (!b) continue; q.bath = true;
      if ((!b.host || h.name === b.host) && h.type !== '卫生间' && len(q) >= .9 && (!bathDoor.has(b.name) || len(q) > len(bathDoor.get(b.name)))) bathDoor.set(b.name, q);
    }
    for (const q of segs) addWall(q.A, q.Bp, q.n, q.a.type, q.o ? q.o.type : null, q.bath ? [...bathDoor.values()].includes(q) : undefined);
    buildLabels();
  }
  function addWall(a, b, n, inside, outside, door) { // inside / outside: room types (outside null = an outer wall); door: false = none, true = a bathroom's door
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
      else if (!ext && door !== false && len >= .9) { const w = len >= 1.3 ? .9 : .7, o0 = -len / 2 + Math.min(.25, (len - w) / 2); ops = [[o0, o0 + w, 0, 2.1, 0]]; }
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
    houseG.add(g); walls.push({ g, kind, n, cur: 1, door: kind === 'int' && ops.length ? [inside, outside] : null });
  }
  // Near-side outer walls drop low so the inside is always visible; interior walls stay half height.
  function wallsStep(force) {
    const vx = Math.sin(cam.az), vz = Math.cos(cam.az); let moving = false;
    for (const w of walls) {
      let tgt = 1; if (w.kind === 'int') tgt = .42;
      if (w.kind === 'ext' && (w.n[0] * vx + w.n[1] * vz) > .25) tgt = .07;
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
  let labelEls = [];
  function buildLabels() {
    labelsEl.innerHTML = ''; labelEls = [];
    for (const n of HM.roomNames(RECTS)) {
      const rs = RECTS.filter(r => r.name === n).sort((a, b) => (b.r[2] - b.r[0]) * (b.r[3] - b.r[1]) - (a.r[2] - a.r[0]) * (a.r[3] - a.r[1]))[0].r;
      const el = document.createElement('div'); el.className = 'hv-label hg'; labelsEl.appendChild(el);
      labelEls.push({ el, n, p: new THREE.Vector3((rs[0] + rs[2]) / 2, .1, (rs[1] + rs[3]) / 2) });
    }
    paintLabels();
  }
  function paintLabels() {
    for (const l of labelEls) {
      const extra = overflow[l.n] ? `<span class="hv-more">+${overflow[l.n]}</span>` : '';
      // With labels hidden, a room that holds more than it can draw still shows its +N on its own.
      const moreOnly = !labelsOn && !!extra;
      l.el.innerHTML = moreOnly ? `<span class="hv-more" role="img" aria-label="${api.esc(l.n)}还有 ${overflow[l.n]} 件没画出来">+${overflow[l.n]}</span>` : `${api.esc(l.n)}${extra}`;
      l.el.hidden = !labelsOn && !moreOnly; l.el.classList.toggle('dim', mode === 'place'); l.el.classList.toggle('more-only', moreOnly);
    }
  }
  const v3 = new THREE.Vector3();
  function project(p) { v3.copy(p); world.localToWorld(v3); v3.project(camera); return [(v3.x + 1) / 2 * canvas.clientWidth, (1 - v3.y) / 2 * canvas.clientHeight]; }
  function updateOverlay() {
    if (view !== 'home') return;
    for (const l of labelEls) { const [x, y] = project(l.p); l.el.style.transform = `translate(${x.toFixed(1)}px,${y.toFixed(1)}px) translate(-50%,-50%)`; }
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
    else { const F = S * 1.47; if (a < 1) { ww = F; hh = ww / a; } else { hh = S * 1.22; ww = hh * a; } up = a < 1 ? (mode === 'place' ? .42 : .54) : .5; }
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
  let gesture = null, drag = null, inertia = null, selected = null;
  function beginOne(x, y) {
    inertia = null;
    if (mode === 'place') {
      const e = itemAt(x, y);
      if (e) { select(e); const p = floorHit(x, y); if (p) { drag = { e, off: [e.x - p.x, e.z - p.z], moved: false, last: [e.x, e.z] }; e.g.position.y = .08; } gesture = { kind: 'drag', x, y, t: performance.now() }; invalidate(); return; }
    }
    gesture = { kind: 'rotate', x, y, lx: x, ly: y, t: performance.now(), moved: false };
  }
  function moveOne(x, y, ts) {
    if (!gesture) return;
    if (gesture.kind === 'drag' && drag) {
      const p = floorHit(x, y); if (!p) return;
      if (Math.hypot(x - gesture.x, y - gesture.y) > 4) drag.moved = true;
      if (!drag.moved) return; // a tap that wobbles a few pixels never moves it unsaved
      const bb = HM.bbox(RECTS, drag.e.room); if (!bb) return;
      const tx = p.x + drag.off[0], tz = p.z + drag.off[1];
      // Heading of the drag over the last ~5 cm: the wall it is pushed towards is the one it may back onto. Right after
      // the heading point moves on, the new heading is too short to count, so the previous one stands in for it, but
      // only while the finger still goes roughly that way (within 60°): after a turn, a stale heading would pick the
      // wall the finger is leaving, so until the new heading counts there is none.
      if (!drag.last) drag.last = [tx, tz]; let mv = { dx: tx - drag.last[0], dz: tz - drag.last[1] }; const len = Math.hypot(mv.dx, mv.dz), hd = drag.hd;
      if (len > .05) { drag.last = [tx, tz]; drag.hd = mv; } else if (len < .03 && hd && (len < .01 || mv.dx * hd.dx + mv.dz * hd.dz > .5 * len * Math.hypot(hd.dx, hd.dz))) mv = hd;
      const r = HM.clampPlacement(HM.KINDS[drag.e.model.kind], bb, tx, tz, drag.e.rot, { ...mv, on: !!drag.snap }); drag.snap = r.snap;
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
  if (window.PointerEvent) {
    canvas.addEventListener('pointerdown', e => {
      if (view !== 'home' || e.pointerType === 'touch' || e.button !== 0) return;
      e.preventDefault(); beginOne(e.clientX, e.clientY);
      const mv = ev => moveOne(ev.clientX, ev.clientY), up = () => { removeEventListener('pointermove', mv); removeEventListener('pointerup', up); removeEventListener('pointercancel', up); endOne(); };
      addEventListener('pointermove', mv); addEventListener('pointerup', up); addEventListener('pointercancel', up);
    });
  } else {
    canvas.addEventListener('mousedown', e => { if (view !== 'home') return; e.preventDefault(); beginOne(e.clientX, e.clientY); });
    addEventListener('mousemove', e => { if (gesture) moveOne(e.clientX, e.clientY); });
    addEventListener('mouseup', () => { if (gesture) endOne(); });
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
    // 1.11.0: a piece of a bundle names the record it belongs to; 拿走 takes only this piece's look.
    const b = e?.item.bundle;
    if (e) q('.hv-who[data-hv="relook"]').innerHTML = `<b>${api.esc(e.name)}</b><span>${b ? api.esc(`${e.room} · 属于${b.name}（共 ${b.n} 件）`) : `${api.esc(itemLine(e.item))} · ${api.esc(e.room)}${HM.KINDS[e.model.kind].wall ? ' · 贴墙摆放' : ''}`}</span>`;
    updateRing(); invalidate();
  }
  // A failed save puts the piece back where the ledger has it, so the screen never shows a spot that will not survive a restart.
  const saveGen = new Map();
  function saveModel(e) { const gen = (saveGen.get(e.id) || 0) + 1; saveGen.set(e.id, gen); api.saveModel(e.id, { ...e.model }).then(ok => { if (ok || saveGen.get(e.id) !== gen) return; if (drag && drag.e === e) { e.rollback = true; return; } api.toast('没保存上，已放回原位，请再试一次', null, true); rollback(e); }); }
  // Back to the ledger's spot for this one piece; the others keep what they saved.
  function rollback(e) { e.rollback = false; const i = api.data().items.find(x => x.id === e.id); if (i?.model) { e.model = { ...i.model }; placeEntry(e); applyFit(); } updateRing(); invalidate(); }
  // A failure that lands mid-drag waits for the finger: a real move saves anew, a mere touch rolls back.
  function endDrag() { const d = drag; drag = null; d.e.g.position.y = 0; if (d.moved) { d.e.rollback = false; saveModel(d.e); } else if (d.e.rollback) { api.toast('没保存上，已放回原位，请再试一次', null, true); rollback(d.e); } }
  // 1.9.0 · 拼户型 saves the blocks (and renames 主卫/公卫 records that moved rooms) in one write.
  function saveHome(home, renames, quiet) {
    T = HM.planOf(home); planKey = JSON.stringify(HM.sizesOf(T)) + T.key; rebuildAll();
    api.saveHome(home, renames).then(ok => { if (!ok) { if (!quiet) api.toast('户型没保存上，下次打开会回到上次保存的样子，请再调整一次', null, true); } else { if (T) { const ch = setPlan(); syncItems(ch || renames.length > 0); if (view === 'home') renderTray(); invalidate(); } if (!quiet) api.toast('户型已保存'); } });
  }

  /* ---------- modes, tray, sheets ---------- */
  function setMode(m) {
    mode = m; root.dataset.mode = m;
    q('[data-dock="place"]').hidden = m !== 'place';
    const pb = q('[data-hv="place"]'), lb = q('[data-hv="layout"]');
    pb.classList.toggle('on', m === 'place'); lb.hidden = m === 'place';
    pb.lastChild.textContent = m === 'place' ? '完成' : '摆放';
    q('.hv-sub').textContent = m === 'place' ? '点选家具，按住拖动' : '单指转动 · 双指缩放';
    if (m !== 'place') select(null);
    paintLabels(); renderTray(); fit();
    if (view === 'home') resetView(true);
    invalidate();
  }
  // Not placed yet, or placed in a room the plan no longer has (拿掉 in 拼户型): both wait in the tray.
  function trayItems() { const d = api.data(), rooms = new Set(HM.roomNames(RECTS)); return d.items.filter(i => qualifies(i) && !(i.model && HM.KINDS[i.model.kind] && rooms.has(i.room))); }
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
      return `<button type="button" data-tplk="${k}">${HM.planSvg(t)}<b>${t.n}</b><small>约 ${a.toFixed(0)}㎡</small></button>`;
    }).join('');
  }
  function rebuildAll() { buildHouse(); for (const e of items.values()) placeEntry(e); applyFit(); updateRing(); wallsStep(true); renderTray(); invalidate(); }
  // room: a room of the plan picked for a record whose own room is gone (it is filed there in the same write).
  function placeFromTray(id, kind, room) {
    const i = api.data().items.find(x => x.id === id); if (!i) return;
    const to = room || i.room;
    if (!HM.bbox(RECTS, to)) { api.toast(`户型里没有「${to}」，换个户型或改这笔帐的空间`); return; }
    const others = [...items.values()].filter(e => e.room === to && e.shown).map(e => { const [w, d] = HM.footprint(HM.KINDS[e.model.kind], e.rot); return { x: e.x, z: e.z, w, d }; });
    const model = HM.findSpot(kind, RECTS, to, others);
    if (!model) { api.toast(`「${to}」放不下这个外观，换一个小一点的或换个房间`, null, 'info'); return; }
    (room ? api.setRoom(i.id, to, model) : api.saveModel(i.id, model)).then(ok => {
      if (!ok) { api.toast('没放进去，请再试一次', null, true); return; }
      syncItems(); renderTray();
      const e = items.get(i.id);
      if (e && e.shown) { focusRoom(to, 1.5); if (!reduced()) drops.push({ e, t0: performance.now() + 300 }); select(e); }
      else api.toast(`「${to}」放满了，这件计入房间标签的 +N`);
      invalidate();
    });
  }
  // A record whose room is no longer in the plan (拿掉, or another template): pick a room of the plan for it.
  let roomPickFor = null;
  function openRoomPick(i) {
    roomPickFor = i.id;
    q('.hv-room-hint').textContent = `「${i.name}」记在「${i.room}」，户型里已经没有这个空间。放到哪个房间？这笔帐的空间也会跟着改。`;
    q('.hv-rooms').innerHTML = HM.roomNames(RECTS).map(n => `<button type="button" class="hg gbtn" data-rpick="${api.esc(n)}">${api.esc(n)}</button>`).join('');
    openSheet('room');
  }
  // Glass press glow on buttons, for touch and mouse alike.
  const press = (b, x, y) => { const r = b.getBoundingClientRect(); b.style.setProperty('--px', ((x - r.left) / r.width * 100) + '%'); b.style.setProperty('--py', ((y - r.top) / r.height * 100) + '%'); b.classList.add('pressed'); setTimeout(() => b.classList.remove('pressed'), 260); };
  root.addEventListener('touchstart', e => { const b = e.target.closest('.gbtn'); if (b && e.touches[0]) press(b, e.touches[0].clientX, e.touches[0].clientY); }, { passive: true });
  root.addEventListener('mousedown', e => { const b = e.target.closest('.gbtn'); if (b) press(b, e.clientX, e.clientY); });
  const onClick = e => {
    const tray = e.target.closest('[data-tray]');
    if (tray) { const id = tray.dataset.tray, i = api.data().items.find(x => x.id === id); if (!i) return; if (tray.classList.contains('off')) { openRoomPick(i); return; } openPicker({ cb: kind => placeFromTray(id, kind), hint: `给「${i.name}」挑一个样子相近的，放进${i.room}。` }); return; }
    const rp = e.target.closest('[data-rpick]');
    if (rp) { const i = api.data().items.find(x => x.id === roomPickFor), room = rp.dataset.rpick; closeSheet(q('[data-sheet="room"]')); if (i) openPicker({ cb: kind => placeFromTray(i.id, kind, room), hint: `给「${i.name}」挑一个样子相近的，放进${room}。` }); return; }
    const tile = e.target.closest('[data-look]');
    if (tile) { pick.sel = tile.dataset.look; qa('.hv-tile').forEach(t => { t.classList.toggle('sel', t === tile); t.setAttribute('aria-pressed', String(t === tile)); }); q('[data-hv="pick-ok"]').disabled = false; return; }
    const tk = e.target.closest('[data-tplk]');
    if (tk) { const t = HM.planOf({ template: tk.dataset.tplk }); closeSheet(q('[data-sheet="tpl"]')); editor.useTemplate(t, t.n); return; }
    const b = e.target.closest('[data-hv]'); if (!b || b.disabled) return;
    const a = b.dataset.hv;
    if (a === 'place') setMode(mode === 'place' ? 'normal' : 'place');
    else if (a === 'layout') { setMode('normal'); editor.open(T); }
    else if (a === 'reset') resetView(true);
    else if (a === 'labels') { labelsOn = !labelsOn; paintLabelBtn(); paintLabels(); api.saveUi({ labels: labelsOn }); api.toast(labelsOn ? '已显示房间标签' : '已隐藏房间标签'); }
    else if (a === 'close') closeSheet(b.closest('.hv-sheet'));
    else if (a === 'pick-ok') { if (!pick.sel) return; const cb = pick.cb; const sel = pick.sel; closeSheet(q('[data-sheet="pick"]')); cb && cb(sel); }
    else if (a === 'rot') { const e2 = selected; if (!e2) return; if (HM.KINDS[e2.model.kind].wall) { api.toast('挂墙的东西会自动朝向屋内', null, 'info'); return; } const r = HM.clampPlacement(HM.KINDS[e2.model.kind], HM.bbox(RECTS, e2.room), e2.x, e2.z, (e2.rot + 1) % 4); e2.model = { ...e2.model, x: +r.fx.toFixed(4), z: +r.fz.toFixed(4), rot: r.rot }; placeEntry(e2); updateRing(); saveModel(e2); invalidate(); }
    else if (a === 'drop') select(null);
    // 拿走: the look leaves the house (back to the tray); the record and its money stay. The notice can put it back.
    else if (a === 'take') { const e2 = selected; if (!e2) return; const id = e2.id, was = e2.model; select(null);
      api.saveModel(id, null).then(ok => { if (!ok) { api.toast('没拿走，请再试一次', null, true); return; } syncItems(); renderTray(); invalidate();
        api.toast(`拿走了${e2.name}，在「还没放进家里的」里`, { label: '撤销', run: () => api.saveModel(id, was).then(ok2 => { if (ok2) { syncItems(); renderTray(); invalidate(); } }) }); }); }
    else if (a === 'relook') { const e2 = selected; if (!e2) return; openPicker({ sel: e2.model.kind, hint: `给「${e2.name}」换一个外观。`, cb: kind => { if (kind === e2.model.kind) return; api.saveModel(e2.id, { ...e2.model, kind }).then(ok => { if (!ok) api.toast('外观没换成，请再试一次', null, true); if (ok) { syncItems(); const n = items.get(e2.id); if (n?.shown) select(n); else select(null); } }); } }); }
  };
  root.addEventListener('click', onClick); layer.addEventListener('click', onClick);
  const editor = createPlanEditor({ root, layer, svg, icons: ICON, esc: api.esc, toast: (m, a) => api.toast(m, a || null, 'info'), openSheet, closeSheet, onTemplates: () => { renderTpl(); openSheet('tpl'); }, onDone: saveHome });
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
    if (kind === 'home') { target.appendChild(root); slotHome.appendChild(canvas); if (was !== 'home') { closeAll(); editor.leave(); setMode('normal'); resetView(false); wallsStep(true); } }
    else { target.appendChild(canvas); closeAll(); editor.leave(); if (mode !== 'normal') { mode = 'normal'; root.dataset.mode = 'normal'; select(null); } cam.pol = VIEW.pol; cam.zoom = 1; cam.tx = cam.tz = 0; tw = null; inertia = null; }
    spinOn = kind === 'mini' && !reduced();
    resizeObs.observe(target); if (kind === 'mini') { onScreen = true; io?.observe(target); }
    sync(); fit(); wallsStep(true); last = 0; invalidate();
  }
  function hide() { closeAll(); view = null; if (raf) cancelAnimationFrame(raf); raf = 0; gesture = drag = pinch = null; editor.leave(); if (host) { resizeObs.unobserve(host); io?.unobserve(host); } host = null; }
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
  // System Back on 我的家: closes a sheet, then 拼户型 (saving it, like 完成), then drops the selection, then leaves
  // place mode. False when nothing is open.
  function back() {
    if (openSheets.length) { closeSheet(); return true; }
    if (editor.isOpen()) { editor.done(); return true; }
    if (selected) { select(null); return true; }
    if (mode !== 'normal') { setMode('normal'); return true; }
    return false;
  }
  // System Back while the look picker is lent to the ledger form: close only that sheet, never the selection or mode.
  function closeTopSheet() { if (!openSheets.length) return false; closeSheet(); return true; }
  function setUi(ui) { if (ui && typeof ui.labels === 'boolean') { labelsOn = ui.labels; paintLabelBtn(); paintLabels(); } }
  paintLabelBtn();
  // Test hooks for the screenshot script: none of these change the ledger.
  const debug = { cam, items, select: id => select(items.get(id) || null), screen: (id, h = .3) => { const e = items.get(id); if (!e) return null; const r = canvas.getBoundingClientRect(); const [x, y] = project(new THREE.Vector3(e.x, h, e.z)); return [r.left + x, r.top + y]; }, mode: () => mode, setMode, editor: () => editor.debug(), doors: () => walls.filter(w => w.door).map(w => w.door) };
  // ToolBox closing with 拼户型 open: save it, like 完成.
  function flush() { if (editor.isOpen()) editor.done(); }
  // ToolBox going to the background with 拼户型 open: save it but keep it open, in case the system then closes ToolBox.
  function saveOpen() { if (editor.isOpen()) editor.save(); }
  return { mount, hide, sync, setUi, openPicker, lendPicker, reveal, arrange, thumbs, back, closeTopSheet, flush, saveOpen, debug };
}

/* ================= 拼户型 ================= */
// 1.9.0 · 拼户型: the floor plan as blocks seen from above. Rooms are rectangles dragged edge to edge (they snap
// within 30 cm and may not overlap); bathrooms are dashed boxes inside a room, at one of its corners, edge middles
// or centre. ➕ 加空间 adds any type, as many as wanted; 拿掉 removes one. Nothing is saved until 完成 (or Back).
// Touch events drive every drag, with mouse fallbacks, because some WebViews send no pointer events.
const PE_SNAP = .3, GRID = .1, ROOM_MIN = 1.5;
const r1 = v => Math.round(v / GRID) * GRID, r2 = v => Math.round(v * 100) / 100;
const fmtA = v => v.toFixed(1) + '㎡';

function createPlanEditor({ root, layer, svg, icons, esc, toast, openSheet, closeSheet, onTemplates, onDone }) {
  const el = document.createElement('div'); el.className = 'pe'; el.hidden = true;
  el.innerHTML = `<div class="pe-top"><div class="hv-title"><h1>拼户型</h1><p class="pe-sum"></p></div><div class="hv-tools"><button type="button" class="hg gbtn gchip" data-pe="tpl">${svg(icons.layout)}<span>范本</span></button><button type="button" class="hg gbtn gchip on" data-pe="done">${svg(icons.check)}<span>完成</span></button></div></div>
  <div class="pe-plan" role="application" aria-label="户型平面，拖动方块拼房间"></div>
  <div class="pe-dock"><div class="hg hv-selbar pe-selbar" hidden><div class="hv-who"><b class="pe-sel-n"></b><span class="pe-sel-s"></span></div><button type="button" class="hg gbtn gchip pe-del" data-pe="del">${svg(icons.trash)}<span>拿掉</span></button></div>
  <div class="pe-addrow"><button type="button" class="hg gbtn gchip pe-add" data-pe="add">${svg(icons.plus)}<span>加空间</span></button></div></div>`;
  root.appendChild(el);
  const sheet = document.createElement('div'); sheet.className = 'hv-sheet hg'; sheet.dataset.sheet = 'space';
  sheet.setAttribute('role', 'dialog'); sheet.setAttribute('aria-modal', 'true'); sheet.setAttribute('aria-label', '加空间');
  sheet.innerHTML = `<div class="hv-grab"></div><div class="hv-sheet-head"><h2>加一个什么空间？</h2><button type="button" class="hg gbtn gcirc" data-hv="close" aria-label="关闭">${svg(icons.close)}</button></div><div class="hv-sheet-body"><p class="hv-hint pe-sheet-hint"></p><div class="pe-types">${HM.SPACE_TYPES.map(([t, w, d]) => `<button type="button" class="hg gbtn${t === '卫生间' ? ' pe-bathbtn' : ''}" data-space="${t}">${t}<small>${w.toFixed(1)} × ${d.toFixed(1)} 米</small></button>`).join('')}</div></div>`;
  layer.appendChild(sheet);
  const $ = s => el.querySelector(s), plan = $('.pe-plan');

  let rooms = [], baths = [], sel = null, startNames = new Map(), startIds = new Set(), living = [], uid = 0, S = 30, OX = 0, OZ = 0, panX = 0, panZ = 0, drag = null;
  const byId = id => rooms.find(r => r.id === id) || baths.find(b => b.id === id);
  const host = b => rooms.find(r => r.id === b.host);
  // A deleted room's name is free again: a new room by that name takes back the records still filed under it.
  const used = skip => new Set([...rooms, ...baths].filter(o => o !== skip).map(o => o.name));
  const areaOf = r => r.w * r.d - baths.filter(b => b.host === r.id).reduce((s, b) => s + b.w * b.d, 0);
  const bathPos = (b, h = host(b), ax = b.ax, az = b.az) => HM.bathBox({ ...b, ax, az }, h);
  const clash = (b, h, ax, az) => { const p = bathPos(b, h, ax, az); return baths.some(o => o !== b && o.host === h.id && HM.boxOverlap(p, bathPos(o))); };
  const isBad = r => rooms.some(o => o !== r && HM.boxOverlap(r, o));
  // A bathroom always leaves at least BLOCK_MIN of its room across one side, so the room never disappears under it.
  const leaves = (b, h) => b.w <= h.w - HM.BLOCK_MIN + 1e-6 || b.d <= h.d - HM.BLOCK_MIN + 1e-6;
  const fitsBaths = r => baths.filter(b => b.host === r.id).every(b => b.w <= r.w + 1e-6 && b.d <= r.d + 1e-6 && leaves(b, r) && !clash(b, r, b.ax, b.az));
  const newId = p => { let id; do id = p + (++uid); while (byId(id) || startIds.has(id)); return id; };

  // Draft v3: 客厅 and 餐厅 are separate rooms. An old 客餐厅 (a template, or a plan saved by 1.9.0–1.9.1) becomes 客厅;
  // of an L-shaped one (two touching blocks) the bigger part becomes 客厅 and the other 餐厅. Its records follow to 客厅
  // when the plan is saved.
  function splitLiving() {
    const out = [];
    for (const n of new Set(rooms.filter(r => r.type === '客餐厅').map(r => r.name))) {
      const parts = rooms.filter(r => r.type === '客餐厅' && r.name === n).sort((a, b) => b.w * b.d - a.w * a.d);
      parts.forEach((r, i) => { r.type = i ? '餐厅' : '客厅'; r.name = HM.nextName(r.type, used(r)); });
      out.push([n, parts[0].name]);
    }
    for (const [a, b] of out) if (!living.some(x => x[0] === a)) living.push([a, b]);
  }
  function open(t) {
    const b = HM.toBlocks(t); rooms = b.blocks; baths = b.baths; sel = null; living = []; panX = panZ = 0;
    startNames = wetNames(); startIds = new Set([...rooms, ...baths].map(x => x.id)); splitLiving();
    el.hidden = false; root.dataset.blocks = '1'; requestAnimationFrame(() => { fit(); render(); });
  }
  function close() { el.hidden = true; delete root.dataset.blocks; sel = null; drag = null; }
  // A template replaces the whole plan, so the notice offers to put the old one back.
  function useTemplate(t, name) {
    const prev = { rooms: rooms.map(r => ({ ...r })), baths: baths.map(b => ({ ...b })), living: living.slice() }, b = HM.toBlocks(t);
    rooms = b.blocks; baths = b.baths; sel = null; splitLiving(); fit(); render();
    toast(`已换成${name}，再拖方块调整`, { label: '撤销', run: () => { if (el.hidden) return; cancelDrag(); ({ rooms, baths, living } = prev); sel = null; fit(); render(); toast('已换回原来的户型'); } });
  }
  // 主卫 / 公卫 renames from moving a bathroom between rooms: the ledger's records follow the new name.
  // A drag still under the finger (Back pressed mid-drag) is undone first, so a red overlap is never what gets saved.
  function cancelDrag() { if (!drag) return; const it = drag.it; if (it.bad && drag.o) Object.assign(it, drag.o); it.bad = false; it.drag = null; drag = null; showLines([]); showAnchors(null); }
  function done() {
    cancelDrag();
    const home = HM.sanitizeBlocks({ blocks: rooms, baths }); if (!home) { toast('至少要留一个房间'); return; }
    // The save never drops a block quietly: anything it would drop keeps the editor open to fix.
    if (home.blocks.length < rooms.length || home.baths.length < baths.length) { render(); toast('有房间的位置存不了（叠在一起或太远），请调整后再按完成'); return; }
    close(); onDone(home, renamesOf(home));
  }
  // Bathrooms, inside a room or standing alone, by id: one moved, or turned from one into the other, keeps its records.
  function wetNames(home = { blocks: rooms, baths }) { return new Map([...home.baths, ...home.blocks.filter(r => r.type === '卫生间')].map(x => [x.id, x.name])); }
  function renamesOf(home) { const renames = living.slice(); for (const b of [...home.baths, ...home.blocks.filter(r => r.type === '卫生间')]) { const was = startNames.get(b.id); if (was && was !== b.name) renames.push([was, b.name]); } return renames; }
  // Saved without closing (ToolBox sent to the background): later saves count renames from here.
  function save() {
    cancelDrag(); render();
    const home = HM.sanitizeBlocks({ blocks: rooms, baths }); if (!home || home.blocks.length < rooms.length || home.baths.length < baths.length) return;
    onDone(home, renamesOf(home), true); living = []; startNames = wetNames(home);
  }
  // Leaving 我的家 by the tabs saves the plan, the same as 完成 and Back.
  function leave() { if (!el.hidden) done(); close(); }

  /* ---------- layout ---------- */
  function bounds() { const xs = rooms.flatMap(r => [r.x, r.x + r.w]), zs = rooms.flatMap(r => [r.z, r.z + r.d]); return { x0: Math.min(...xs, 0), z0: Math.min(...zs, 0), x1: Math.max(...xs, 4), z1: Math.max(...zs, 4) }; }
  function fit() {
    const b = bounds(), pw = plan.clientWidth || 320, ph = plan.clientHeight || 400, pad = 14;
    // Never below 12 px a metre, so every block stays big enough to touch; a plan wider than the screen is dragged
    // into view by its empty background instead.
    S = Math.max(12, Math.min(40, (pw - pad * 2) / (b.x1 - b.x0 + 1), (ph - pad * 2) / (b.z1 - b.z0 + 1)));
    const cw = (b.x1 - b.x0) * S, ch = (b.z1 - b.z0) * S, vw = pw - pad * 2, vh = ph - pad * 2;
    panX = cw <= vw ? 0 : Math.min(0, Math.max(vw - cw, panX)); panZ = ch <= vh ? 0 : Math.min(0, Math.max(vh - ch, panZ));
    OX = pad + (cw <= vw ? (vw - cw) / 2 : panX) - b.x0 * S; OZ = pad + (ch <= vh ? (vh - ch) / 2 : panZ) - b.z0 * S;
    plan.classList.toggle('pan', cw > vw || ch > vh);
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
      place(e, b.drag || bathPos(b)); html(e, `<span class="pe-bt">${esc(b.name)}<small>${fmtA(b.w * b.d)}</small></span>`);
      e.setAttribute('aria-label', `${b.name}，在${host(b).name}里`);
    }
    for (const [id, e] of els) if (!live.has(id)) { e.remove(); els.delete(id); }
    const it = byId(sel);
    // A bathroom's handles sit on the edges that move: the side away from the wall it is anchored to.
    const keys = () => { const p = it.host ? bathPos(it) : it; return it.host ? [it.ax === 1 ? ['l', p.x, p.z + p.d / 2] : ['r', p.x + p.w, p.z + p.d / 2], it.az === 1 ? ['t', p.x + p.w / 2, p.z] : ['b', p.x + p.w / 2, p.z + p.d]] : [['l', p.x, p.z + p.d / 2], ['r', p.x + p.w, p.z + p.d / 2], ['t', p.x + p.w / 2, p.z], ['b', p.x + p.w / 2, p.z + p.d]]; };
    // While a handle is dragged its node must stay: a touch whose target leaves the page sends no more touchmove or
    // touchend, so the handles only follow the edge until the finger lifts.
    if (drag && drag.handle) { const ks = keys(); for (const h of plan.querySelectorAll('.pe-hdl')) { const k = ks.find(q => q[0] === h.dataset.k); if (k) { h.style.left = px(k[1]) + 'px'; h.style.top = pz(k[2]) + 'px'; } } }
    else {
      plan.querySelectorAll('.pe-hdl').forEach(h => h.remove());
      if (it && !drag) for (const [k, x, z] of keys()) { const h = document.createElement('div'); h.className = 'pe-hdl'; h.dataset.k = k; h.style.left = px(x) + 'px'; h.style.top = pz(z) + 'px'; h.setAttribute('aria-label', '拖动改大小'); plan.appendChild(h); bindHandle(h, it); }
    }
    const tot = rooms.filter(r => r.type !== '阳台').reduce((s, r) => s + r.w * r.d, 0);
    html($('.pe-sum'), `<span>套内约 ${Math.round(tot)}㎡</span> · <span>${baths.length + rooms.filter(r => r.type === '卫生间').length} 个卫生间</span>`);
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
      for (const [me, them] of [[r.x, o.x], [r.x, o.x + o.w], [r.x + r.w, o.x], [r.x + r.w, o.x + o.w]]) { const d = them - me; if (Math.abs(d) < PE_SNAP && (!bx || Math.abs(d) < Math.abs(bx.d))) bx = { d, v: them }; }
      for (const [me, them] of [[r.z, o.z], [r.z, o.z + o.d], [r.z + r.d, o.z], [r.z + r.d, o.z + o.d]]) { const d = them - me; if (Math.abs(d) < PE_SNAP && (!bz || Math.abs(d) < Math.abs(bz.d))) bz = { d, v: them }; } }
    if (bx) { r.x = r2(r.x + bx.d); ls.push(['x', bx.v]); } if (bz) { r.z = r2(r.z + bz.d); ls.push(['z', bz.v]); }
    return ls;
  }
  function snapEdge(r, k) {
    const ax = k === 'l' || k === 'r' ? 'x' : 'z', v = k === 'l' ? r.x : k === 'r' ? r.x + r.w : k === 't' ? r.z : r.z + r.d; let best = null;
    for (const o of rooms) { if (o === r) continue; for (const t of ax === 'x' ? [o.x, o.x + o.w] : [o.z, o.z + o.d]) { const d = t - v; if (Math.abs(d) < PE_SNAP && (!best || Math.abs(d) < Math.abs(best.d))) best = { d, t }; } }
    if (!best) return [];
    if (k === 'l') { r.w -= best.d; r.x += best.d; } else if (k === 'r') r.w += best.d; else if (k === 't') { r.d -= best.d; r.z += best.d; } else r.d += best.d;
    return [[ax, best.t]];
  }
  const q3 = v => v < .25 ? 0 : v > .75 ? 1 : .5;
  function dropHost(b) {
    const cx = b.drag.x + b.w / 2, cz = b.drag.z + b.d / 2;
    const h = rooms.find(o => !HM.NOHOST.has(o.type) && cx > o.x && cx < o.x + o.w && cz > o.z && cz < o.z + o.d && b.w <= o.w && b.d <= o.d && leaves(b, o)); if (!h) return null;
    const ax = q3(h.w > b.w ? (b.drag.x - h.x) / (h.w - b.w) : .5), az = q3(h.d > b.d ? (b.drag.z - h.z) / (h.d - b.d) : .5);
    return clash(b, h, ax, az) ? { h, clash: 1 } : { h, ax, az };
  }
  // Two blocks share a name (an old L-shaped 客餐厅) only while they touch; pulled apart, the later one counts on now,
  // the way the save would name it, so the editor never shows a name the save changes.
  function splitNames() {
    for (const [i, o] of rooms.entries()) { const same = rooms.slice(0, i).filter(q => q.name === o.name); if (!same.length || same.every(q => q.type === o.type && HM.touch(q, o))) continue;
      const was = o.name; o.name = HM.nextName(o.type, used(o)); toast(`两块${was}分开了，这一块改叫${o.name}`); }
  }
  function rename(b) { b.name = HM.nextName(HM.bathName(host(b)), used(b)); }
  // A bathroom dragged out of every room onto empty floor stands alone, as a 卫生间 block keeping its id and name.
  function freeBath(b) { const r = { id: b.id, type: '卫生间', name: b.name, x: r2(r1(b.drag.x)), z: r2(r1(b.drag.z)), w: b.w, d: b.d }; return Math.abs(r.x) <= 60 && Math.abs(r.z) <= 60 && r.x + r.w <= 60 && r.z + r.d <= 60 && !rooms.some(o => HM.boxOverlap(r, o)) ? r : null; }
  // A standalone 卫生间 dropped with its centre in a room it fits becomes that room's bathroom (renamed 主卫/公卫).
  function intoRoom(r) {
    const b = { id: r.id, w: r.w, d: r.d, drag: { x: r.x, z: r.z } }, d = dropHost(b); if (!d || d.clash) return null;
    const n = { id: r.id, host: d.h.id, w: r.w, d: r.d, ax: d.ax, az: d.az, name: r.name }; n.name = HM.nextName(HM.bathName(d.h), used(r)); return n;
  }

  /* ---------- gestures: touch first, mouse as a fallback ---------- */
  function track(target, start, self) { // self: only gestures that start on the target itself, not on what is inside it
    target.addEventListener('touchstart', ev => { if (ev.touches.length > 1 || (self && ev.target !== target)) return; ev.preventDefault(); ev.stopPropagation(); const t = ev.touches[0], g = start(t.clientX, t.clientY); if (!g) return;
      const mv = e => { const u = [...e.touches].find(x => x.identifier === t.identifier); if (u) { e.preventDefault(); g.move(u.clientX, u.clientY); } };
      // On the document: a re-render may replace the node the finger first touched, and its events would stop there.
      const up = e => { if ([...e.touches].some(x => x.identifier === t.identifier)) return; document.removeEventListener('touchmove', mv); document.removeEventListener('touchend', up); document.removeEventListener('touchcancel', up); g.end(); };
      document.addEventListener('touchmove', mv, { passive: false }); document.addEventListener('touchend', up); document.addEventListener('touchcancel', up); }, { passive: false });
    target.addEventListener('mousedown', ev => { if (ev.button || (self && ev.target !== target)) return; ev.preventDefault(); ev.stopPropagation(); const g = start(ev.clientX, ev.clientY); if (!g) return;
      const mv = e => g.move(e.clientX, e.clientY), up = () => { removeEventListener('mousemove', mv); removeEventListener('mouseup', up); g.end(); };
      addEventListener('mousemove', mv); addEventListener('mouseup', up); });
  }
  function bindDrag(e) {
    track(e, (sx, sy) => {
      const it = byId(e.dataset.id); if (!it) return null; sel = it.id;
      const o = it.host ? bathPos(it) : { x: it.x, z: it.z }; drag = { it, moved: false, o: it.host ? null : o }; render();
      return {
        move(x, y) {
          if (!drag || drag.it !== it) return; // undone by Back mid-drag
          const dx = (x - sx) / S, dz = (y - sy) / S; if (Math.hypot(dx, dz) * S > 4) drag.moved = true; if (!drag.moved) return;
          if (!it.host) { it.x = r1(Math.min(60 - it.w, Math.max(-60, o.x + dx))); it.z = r1(Math.min(60 - it.d, Math.max(-60, o.z + dz))); showLines(snapMove(it)); it.bad = isBad(it); }
          else { it.drag = { x: o.x + dx, z: o.z + dz }; const d = dropHost(it); it.bad = d ? !!d.clash : !freeBath(it); showAnchors(d && d.h, it); }
          render();
        },
        end() {
          if (!drag || drag.it !== it) return;
          showLines([]); showAnchors(null);
          const into = !it.host && it.bad && it.type === '卫生间' ? intoRoom(it) : null;
          if (into) { rooms = rooms.filter(x => x !== it); baths.push(into); sel = into.id; toast(`${into.name}放进了${host(into).name}里`); }
          else if (!it.host && it.bad) { it.x = o.x; it.z = o.z; it.bad = false; toast('房间不能叠在一起，已放回原位'); } else if (!it.host) splitNames();
          if (it.host && it.drag) { const d = dropHost(it);
            if (d && !d.clash) { const was = it.name, moved = d.h.id !== it.host; it.host = d.h.id; it.ax = d.ax; it.az = d.az; if (moved) { rename(it); toast(it.name === was ? `${was}改放在${d.h.name}里` : `${was}改放在${d.h.name}里，现在叫${it.name}`); } }
            else if (!d && freeBath(it)) { const r = freeBath(it); baths = baths.filter(x => x !== it); delete it.drag; rooms.push(r); sel = r.id; toast(`${r.name}改成独立的卫生间`); }
            else toast(d ? '跟另一个卫生间叠到了，已放回原位' : '这里会叠到别的房间，已放回原位');
            it.drag = null; it.bad = false; }
          drag = null; fit(); render();
        }
      };
    });
  }
  function bindHandle(h, it) {
    track(h, (sx, sy) => {
      // Rooms go down to 1.5 m (the design), or stay as small as an old template made them.
      const k = h.dataset.k, o = { x: it.x, z: it.z, w: it.w, d: it.d }, MIN = Math.min(ROOM_MIN, k === 'l' || k === 'r' ? o.w : o.d); drag = { it, moved: true, handle: true, o };
      return {
        move(x, y) {
          if (!drag || drag.it !== it) return; // undone by Back mid-drag
          const dx = r1((x - sx) / S), dz = r1((y - sy) / S);
          if (it.host) { const hr = host(it), g = (v, a, s) => v * s * (a === .5 ? 2 : 1), M = HM.BLOCK_MIN - 1e-6;
            // Full width only while it leaves 1.2 m of the room the other way, and the same for depth.
            if (k === 'l' || k === 'r') it.w = r2(Math.max(HM.BATH_MIN, Math.min(it.d <= hr.d - M ? hr.w : hr.w - HM.BLOCK_MIN, o.w + g(dx, it.ax, k === 'l' ? -1 : 1))));
            else it.d = r2(Math.max(HM.BATH_MIN, Math.min(it.w <= hr.w - M ? hr.d : hr.d - HM.BLOCK_MIN, o.d + g(dz, it.az, k === 't' ? -1 : 1))));
            it.bad = clash(it, hr, it.ax, it.az); render(); return; }
          Object.assign(it, o);
          if (k === 'l') { const v = Math.min(dx, o.w - MIN); it.x = o.x + v; it.w = o.w - v; } else if (k === 'r') it.w = Math.max(MIN, o.w + dx);
          else if (k === 't') { const v = Math.min(dz, o.d - MIN); it.z = o.z + v; it.d = o.d - v; } else it.d = Math.max(MIN, o.d + dz);
          showLines(snapEdge(it, k)); it.x = r2(it.x); it.z = r2(it.z); it.w = Math.max(MIN, r2(it.w)); it.d = Math.max(MIN, r2(it.d));
          // The same bounds the save keeps (30 m a side, ±60 m), so what the editor shows is what is saved.
          if (k === 'l' && it.x < -60) { it.w = r2(it.x + it.w + 60); it.x = -60; } if (k === 't' && it.z < -60) { it.d = r2(it.z + it.d + 60); it.z = -60; }
          if (it.w > 30) { if (k === 'l') it.x = r2(it.x + it.w - 30); it.w = 30; } if (it.d > 30) { if (k === 't') it.z = r2(it.z + it.d - 30); it.d = 30; } it.bad = isBad(it) || !fitsBaths(it); render();
        },
        end() { if (!drag || drag.it !== it) return; showLines([]); if (it.bad) { const why = it.host ? '会跟另一个卫生间叠到' : isBad(it) ? '这样会叠到别的房间' : '这样放不下里面的卫生间'; Object.assign(it, o); it.bad = false; toast(`${why}，已改回原来大小`); } else if (!it.host) splitNames(); drag = null; fit(); render(); }
      };
    });
  }
  // The empty background: a tap drops the selection; a drag moves a plan bigger than the screen.
  track(plan, (sx, sy) => {
    const x0 = panX, z0 = panZ; let moved = false;
    return { move(x, y) { if (Math.hypot(x - sx, y - sy) > 6) moved = true; if (!moved || !plan.classList.contains('pan')) return; panX = x0 + x - sx; panZ = z0 + y - sy; fit(); render(); }, end() { if (!moved) { sel = null; render(); } } };
  }, true);

  /* ---------- ➕ add, 拿掉 ---------- */
  function freeSpot(w, d) {
    const b = bounds();
    for (let z = b.z0; z <= b.z1 + .01; z += .3) for (let x = b.x0; x <= b.x1 + .01; x += .3) { const c = { x: r2(r1(x)), z: r2(r1(z)), w, d }; if (c.x >= -60 && c.z >= -60 && c.x + w <= 60 && c.z + d <= 60 && !rooms.some(o => HM.boxOverlap(c, o))) return c; }
    return { x: r2(r1(b.x1)), z: r2(r1(b.z0)), w, d };
  }
  function bathSpot(b, h) { for (const [ax, az] of [[1, 0], [0, 0], [1, 1], [0, 1], [.5, 0], [.5, 1], [0, .5], [1, .5], [.5, .5]]) if (!clash(b, h, ax, az)) return { ax, az }; return null; }
  const size = t => { const s = HM.SPACE_TYPES.find(x => x[0] === t); return { w: s[1], d: s[2] }; };
  function addBath() {
    if (baths.length >= HM.BATH_MAX) { toast(`最多${HM.BATH_MAX}个卫生间`); return; }
    const b = { id: newId('w'), ...size('卫生间'), host: null, ax: 1, az: 0 };
    const it = byId(sel), picked = it ? (it.host ? host(it) : it) : null;
    // No room picked (or one that can't take it): a bathroom of its own, on free floor, to drag next to the others.
    if (!picked || HM.NOHOST.has(picked.type)) { addRoom('卫生间'); return; }
    const ok = h => h && !HM.NOHOST.has(h.type) && b.w <= h.w && b.d <= h.d && leaves(b, h) && bathSpot(b, h);
    let h = ok(picked) ? picked : null;
    if (!h) { toast(`${picked.name}放不下，改加一个独立的卫生间`); addRoom('卫生间'); return; }
    Object.assign(b, { host: h.id }, bathSpot(b, h)); b.name = HM.nextName(HM.bathName(h), used()); baths.push(b); sel = b.id; render();
    // Picked a room it can't go in: say why, and where it went instead.
    toast(`加了${b.name}，放在${h.name}里，可以拖到别处或拖到空地`);
  }
  function addRoom(type) { if (rooms.length >= HM.BLOCK_MAX) { toast(`最多${HM.BLOCK_MAX}个房间`); return; } const s = size(type), spot = freeSpot(s.w, s.d);
    if (spot.x < -60 || spot.z < -60 || spot.x + s.w > 60 || spot.z + s.d > 60 || rooms.some(o => HM.boxOverlap(spot, o))) { toast('没有空位放新房间了，先挪一挪别的房间'); return; }
    const r = { id: newId('b'), type, name: HM.nextName(type, used()), ...spot }; rooms.push(r); sel = r.id; fit(); render(); toast(`加了${r.name}，拖去贴着别的房间`); }
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
    else if (a === 'add') { const it = byId(sel); sheet.querySelector('.pe-sheet-hint').textContent = it && !it.host && !HM.NOHOST.has(it.type) ? `选「卫生间」会放在${it.name}里；其他放在空位，再拖去拼` : '选好后放在空位，再拖去贴着别的房间；先选中一个房间再加卫生间，就放在那个房间里'; openSheet('space'); }
  });
  sheet.addEventListener('click', e => { const b = e.target.closest('[data-space]'); if (!b) return; closeSheet(sheet); b.dataset.space === '卫生间' ? addBath() : addRoom(b.dataset.space); });
  addEventListener('resize', () => { if (!el.hidden) { fit(); render(); } });
  return { open, close, done, save, leave, useTemplate, isOpen: () => !el.hidden, debug: () => ({ rooms, baths, S, OX, OZ }) };
}
