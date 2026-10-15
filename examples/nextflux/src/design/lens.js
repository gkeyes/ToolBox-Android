// Liquid-glass edge refraction for the floating chrome.
//
// The design boards bend whatever is behind a glass capsule near its rim:
// `backdrop-filter: url(#lens) blur(2px) saturate(180%) brightness(1.05)`,
// where the SVG filter is an feDisplacementMap driven by an edge map. The map
// depends on the element's size and corner radius, so this module draws one
// per size (on a canvas, once), keeps it in a hidden <svg>, and points the
// element's `--nf-lens` at it. ios27.css reads `var(--nf-lens, blur(12px))`:
// without this module, or on an engine that can't take url() in
// backdrop-filter, the chrome stays frosted glass.

const NS = "http://www.w3.org/2000/svg";
const EDGE = 16; // px of rim that bends
const SCALE = 21; // displacement strength, as on the boards (lens 38 × 0.55)

// What gets a lens, and the inset of the painted disc inside the element
// (the close button paints its 44px disc on ::before, 2px inside a 48px target).
const TARGETS = [
  { selector: '.article-list-header > [data-sidebar="trigger"]' },
  { selector: ".article-list-inline-tabs .tabs__list" },
  { selector: ".article-list-scoped-tabs .tabs__list" },
  { selector: ".article-list-actions" },
  { selector: ".article-list-indicator" },
  { selector: ".action-buttons .reader-dock" },
  { selector: ".action-buttons .reader-speech-slot" },
  { selector: ".action-buttons .reader-topbar-row .nextflux-close-button", inset: 2 },
];

export function lensSupported(win = window) {
  return Boolean(win.CSS?.supports?.("backdrop-filter", "url(#nf) blur(2px)"));
}

// Edge displacement map: mid-grey (no shift) inside, pushing inwards along the
// rim's normal over the last EDGE px, strongest at the edge.
export function edgeMap(doc, width, height, radius) {
  const canvas = doc.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) return null;
  const image = context.createImageData(width, height);
  const edge = Math.min(EDGE, radius || EDGE, height / 2, width / 2);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const px = x + 0.5;
      const py = y + 0.5;
      // Distance to the rounded-rect edge and the outward normal there.
      const cx = Math.min(Math.max(px, radius), width - radius);
      const cy = Math.min(Math.max(py, radius), height - radius);
      let nx = px - cx;
      let ny = py - cy;
      let depth;
      const corner = Math.hypot(nx, ny);
      if (corner > 0) {
        depth = radius - corner;
        nx /= corner;
        ny /= corner;
      } else {
        // On a straight side: the nearest side gives depth and normal.
        const sides = [[px, -1, 0], [width - px, 1, 0], [py, 0, -1], [height - py, 0, 1]];
        sides.sort((a, b) => a[0] - b[0]);
        [depth, nx, ny] = sides[0];
      }
      const k = depth < edge ? (1 - Math.max(depth, 0) / edge) ** 2 : 0;
      const i = (y * width + x) * 4;
      image.data[i] = 128 - nx * k * 127;
      image.data[i + 1] = 128 - ny * k * 127;
      image.data[i + 2] = 128;
      image.data[i + 3] = 255;
    }
  }
  context.putImageData(image, 0, 0);
  return canvas.toDataURL();
}

export function installLens({ doc = document, win = window } = {}) {
  if (!lensSupported(win) || typeof win.ResizeObserver !== "function") return null;
  const svg = doc.createElementNS(NS, "svg");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("width", "0");
  svg.setAttribute("height", "0");
  svg.style.cssText = "position:absolute;width:0;height:0;overflow:hidden;pointer-events:none";
  svg.dataset.nfLens = "";
  doc.body.append(svg);

  const filters = new Map(); // "w×h×r" -> filter id
  const bound = new Map(); // element -> inset
  const filterFor = (width, height, radius) => {
    const key = `${width}x${height}r${radius}`;
    if (filters.has(key)) return filters.get(key);
    const href = edgeMap(doc, width, height, radius);
    if (!href) return null;
    const id = `nf-lens-${filters.size}`;
    const filter = doc.createElementNS(NS, "filter");
    const attrs = { id, x: 0, y: 0, width, height, filterUnits: "userSpaceOnUse", primitiveUnits: "userSpaceOnUse", "color-interpolation-filters": "sRGB" };
    for (const [name, value] of Object.entries(attrs)) filter.setAttribute(name, String(value));
    const map = doc.createElementNS(NS, "feImage");
    for (const [name, value] of Object.entries({ href, x: 0, y: 0, width, height, preserveAspectRatio: "none", result: "map" })) map.setAttribute(name, String(value));
    const displace = doc.createElementNS(NS, "feDisplacementMap");
    for (const [name, value] of Object.entries({ in: "SourceGraphic", in2: "map", scale: SCALE, xChannelSelector: "R", yChannelSelector: "G" })) displace.setAttribute(name, String(value));
    filter.append(map, displace);
    svg.append(filter);
    filters.set(key, id);
    return id;
  };

  const update = (element) => {
    const inset = bound.get(element) ?? 0;
    const rect = element.getBoundingClientRect();
    const width = Math.round(rect.width) - inset * 2;
    const height = Math.round(rect.height) - inset * 2;
    if (width < 8 || height < 8) return;
    const style = win.getComputedStyle(element, inset ? "::before" : null);
    const radius = Math.min(parseFloat(style.borderTopLeftRadius) || 0, width / 2, height / 2);
    const id = filterFor(width, height, Math.round(radius));
    if (id) element.style.setProperty("--nf-lens", `url(#${id})`);
  };

  const resizes = new win.ResizeObserver((entries) => { for (const entry of entries) update(entry.target); });
  const scan = () => {
    for (const element of bound.keys()) {
      if (!element.isConnected) { resizes.unobserve(element); bound.delete(element); }
    }
    for (const { selector, inset = 0 } of TARGETS) {
      for (const element of doc.querySelectorAll(selector)) {
        if (bound.has(element)) continue;
        bound.set(element, inset);
        resizes.observe(element);
      }
    }
  };
  let frame = 0;
  const schedule = () => {
    if (frame) return;
    frame = win.requestAnimationFrame(() => { frame = 0; scan(); });
  };
  const mutations = new win.MutationObserver(schedule);
  mutations.observe(doc.body, { childList: true, subtree: true });
  scan();
  return {
    scan,
    dispose() {
      mutations.disconnect();
      resizes.disconnect();
      if (frame) win.cancelAnimationFrame(frame);
      for (const element of bound.keys()) element.style.removeProperty("--nf-lens");
      svg.remove();
    },
  };
}
