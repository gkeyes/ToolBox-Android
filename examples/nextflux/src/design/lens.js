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
const REGION = 4096; // px of filter region around the element (see filterFor)

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
  // Standalone glass buttons (design/glass-buttons.css). Inside sheets and
  // dialogs a url() filter drops the blur in Chromium, so they stay frosted there.
  { selector: ".button:is(.button--primary, .button--secondary, .button--tertiary, .button--danger):not(.nf-set-row)", outside: ".nextflux-modal-surface, .toolbox-link-dialog, .dropdown__popover, .popover" },
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

  const filters = new Map(); // "w×h×r" -> { id, node }
  const bound = new Map(); // element -> { inset, key }
  let nextId = 0;
  const filterFor = (key, width, height, radius) => {
    if (filters.has(key)) return filters.get(key).id;
    const href = edgeMap(doc, width, height, radius);
    if (!href) return null;
    const id = `nf-lens-${nextId++}`;
    const filter = doc.createElementNS(NS, "filter");
    // The region is far larger than the element and the map sits on neutral
    // grey: Chromium leaves any backdrop outside the region unfiltered (losing
    // the blur too), and inside some overlays its filter space doesn't start at
    // the element. There the map lands elsewhere and the glass stays plain
    // frosted instead of losing its blur.
    const attrs = { id, x: -REGION, y: -REGION, width: REGION * 2 + width, height: REGION * 2 + height, filterUnits: "userSpaceOnUse", primitiveUnits: "userSpaceOnUse", "color-interpolation-filters": "sRGB" };
    for (const [name, value] of Object.entries(attrs)) filter.setAttribute(name, String(value));
    const neutral = doc.createElementNS(NS, "feFlood");
    for (const [name, value] of Object.entries({ "flood-color": "rgb(128,128,128)", result: "neutral" })) neutral.setAttribute(name, String(value));
    const image = doc.createElementNS(NS, "feImage");
    for (const [name, value] of Object.entries({ href, x: 0, y: 0, width, height, preserveAspectRatio: "none", result: "edge" })) image.setAttribute(name, String(value));
    const merge = doc.createElementNS(NS, "feMerge");
    merge.setAttribute("result", "map");
    for (const input of ["neutral", "edge"]) {
      const node = doc.createElementNS(NS, "feMergeNode");
      node.setAttribute("in", input);
      merge.append(node);
    }
    const displace = doc.createElementNS(NS, "feDisplacementMap");
    for (const [name, value] of Object.entries({ in: "SourceGraphic", in2: "map", scale: SCALE, xChannelSelector: "R", yChannelSelector: "G" })) displace.setAttribute(name, String(value));
    filter.append(neutral, image, merge, displace);
    svg.append(filter);
    filters.set(key, { id, node: filter });
    return id;
  };

  // Drop elements that left the page and every map no element uses any more,
  // so resizing (split screen, font size) doesn't pile up filters.
  const prune = () => {
    for (const element of bound.keys()) {
      if (!element.isConnected) { resizes.unobserve(element); bound.delete(element); }
    }
    const used = new Set([...bound.values()].map((entry) => entry.key));
    for (const [key, { node }] of filters) {
      if (!used.has(key)) { node.remove(); filters.delete(key); }
    }
  };

  const update = (element) => {
    const entry = bound.get(element);
    if (!entry) return;
    // Layout size, not getBoundingClientRect: a sheet or alert measured while
    // it scales in would get a map smaller than itself, and Chromium leaves the
    // backdrop outside the filter region unfiltered (no blur at all there).
    const width = element.offsetWidth - entry.inset * 2;
    const height = element.offsetHeight - entry.inset * 2;
    if (width < 8 || height < 8) return;
    const style = win.getComputedStyle(element, entry.inset ? "::before" : null);
    const radius = Math.round(Math.min(parseFloat(style.borderTopLeftRadius) || 0, width / 2, height / 2));
    const key = `${width}x${height}r${radius}`;
    if (key === entry.key) return;
    const id = filterFor(key, width, height, radius);
    if (!id) return;
    entry.key = key;
    element.style.setProperty("--nf-lens", `url(#${id})`);
  };

  const resizes = new win.ResizeObserver((entries) => {
    for (const entry of entries) update(entry.target);
    prune();
  });
  const bind = (element) => {
    if (bound.has(element)) return;
    const target = TARGETS.find(({ selector }) => element.matches(selector));
    if (!target || (target.outside && element.closest(target.outside))) return;
    bound.set(element, { inset: target.inset ?? 0, key: null });
    resizes.observe(element);
  };
  const ALL = TARGETS.map(({ selector }) => selector).join(", ");
  // Only the nodes that were added are searched, never the whole document:
  // a long article mounts in many small batches and none of it is chrome.
  const bindWithin = (root) => {
    if (root.matches?.(ALL)) bind(root);
    if (root.firstElementChild) for (const element of root.querySelectorAll(ALL)) bind(element);
  };
  const scan = () => { prune(); bindWithin(doc.body); };

  let added = [];
  let removed = false;
  let frame = 0;
  const flush = () => {
    frame = 0;
    const roots = added;
    added = [];
    if (removed) { removed = false; prune(); }
    for (const root of roots) if (root.isConnected) bindWithin(root);
  };
  const mutations = new win.MutationObserver((records) => {
    for (const record of records) {
      for (const node of record.addedNodes) if (node.nodeType === 1) added.push(node);
      if (record.removedNodes.length) removed = true;
    }
    if (!frame && (added.length || removed)) frame = win.requestAnimationFrame(flush);
  });
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

// Press state for the glass buttons: where the finger is (the sheen follows
// it) and a data-nf-pressed flag, from touch as well as pointer events, since
// some Android WebViews deliver no pointer events for a touch.
const PRESSABLE = ".button:not(.nf-set-row), .tabs__tab, .switch";
const DISABLED = ':disabled, [data-disabled="true"], [aria-disabled="true"]';
export function installGlassPress({ doc = document } = {}) {
  let pressed = null;
  const place = (element, x, y) => {
    const rect = element.getBoundingClientRect();
    element.style.setProperty("--nf-gx", `${Math.round(x - rect.left)}px`);
    element.style.setProperty("--nf-gy", `${Math.round(y - rect.top)}px`);
  };
  const release = () => {
    if (pressed) delete pressed.dataset.nfPressed;
    pressed = null;
  };
  const start = (event) => {
    const point = event.touches ? event.touches[0] : event;
    const element = event.target?.closest?.(PRESSABLE);
    if (!point || !element) return;
    release();
    if (element.matches(DISABLED) || element.querySelector(":scope > input:disabled")) return;
    pressed = element;
    element.dataset.nfPressed = "true";
    place(element, point.clientX, point.clientY);
  };
  const move = (event) => {
    const point = event.touches ? event.touches[0] : event;
    if (!pressed || !point) return;
    const rect = pressed.getBoundingClientRect();
    // A finger that slides well off the button is a scroll or a cancel.
    if (point.clientX < rect.left - 24 || point.clientX > rect.right + 24 || point.clientY < rect.top - 24 || point.clientY > rect.bottom + 24) {
      release();
      return;
    }
    place(pressed, point.clientX, point.clientY);
  };
  const options = { capture: true, passive: true };
  const listeners = [
    ["touchstart", start], ["touchmove", move], ["touchend", release], ["touchcancel", release],
    ["pointerdown", start], ["pointermove", move], ["pointerup", release], ["pointercancel", release],
  ];
  // A mouse released outside the page sends no pointerup here.
  const leave = (event) => { if (!event.relatedTarget) release(); };
  listeners.push(["pointerout", leave]);
  const win = doc.defaultView;
  for (const [type, handler] of listeners) doc.addEventListener(type, handler, options);
  win?.addEventListener("blur", release);
  return () => {
    for (const [type, handler] of listeners) doc.removeEventListener(type, handler, options);
    win?.removeEventListener("blur", release);
    release();
  };
}
