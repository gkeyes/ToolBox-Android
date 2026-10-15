// Defensive "overlay janitor".
//
// Floating layers opened from the reader (sheets, dialogs, the photo
// gallery, media fullscreen) each have their own close path. When one of
// those paths is interrupted (a lost animationend, an unmount mid-exit, a
// WebView pause, a fullscreen the host never shows) the layer can stay on top
// of the page, invisible or not, and swallow every tap. This module checks,
// only while no overlay is supposed to be open, whether something still
// intercepts input over the viewport and releases it.
//
// It is deliberately conservative: it never touches anything inside #root
// (the app shell, including the fixed article page), any visible dialog that
// is not exiting, an open native <dialog>, or anything while the caller says
// an overlay is expected. A candidate is released only if it is still there
// on a second check after a short settle delay.

const SETTLE_MS = 600;
const PROBE_FRACTIONS = [[0.5, 0.5], [0.15, 0.25], [0.85, 0.25], [0.15, 0.75], [0.85, 0.75]];

function viewport(win) {
  return { width: win.innerWidth || 0, height: win.innerHeight || 0 };
}

function fullscreenElement(doc) {
  return doc.fullscreenElement || doc.webkitFullscreenElement || null;
}

// A dialog that is legitimately open: not exiting and actually on screen.
function hasVisibleDialog(element, win) {
  const { width, height } = viewport(win);
  const dialogs = element.matches?.("[role='dialog'], [role='alertdialog']")
    ? [element]
    : [...element.querySelectorAll("[role='dialog'], [role='alertdialog']")];
  return dialogs.some((dialog) => {
    if (dialog.closest("[data-exiting='true']")) return false;
    const rect = dialog.getBoundingClientRect();
    const visibleHeight = Math.min(rect.bottom, height) - Math.max(rect.top, 0);
    const visibleWidth = Math.min(rect.right, width) - Math.max(rect.left, 0);
    return visibleHeight >= 40 && visibleWidth >= 40;
  });
}

// The top-level body child (outside the app shell) that owns a hit target.
function portalOwner(doc, hit) {
  let node = hit;
  while (node && node.parentElement && node.parentElement !== doc.body) node = node.parentElement;
  if (!node || node.parentElement !== doc.body) return null;
  if (node.id === "root" || node.id === "splash-screen" || node.tagName === "SCRIPT") return null;
  return node;
}

// The outermost element of the owner that covers most of the viewport.
function coveringLayer(owner, hit, win) {
  const { width, height } = viewport(win);
  const area = width * height;
  if (!area) return null;
  for (let node = hit; node; node = node.parentElement) {
    const style = win.getComputedStyle(node);
    if ((style.position === "fixed" || style.position === "absolute") && style.pointerEvents !== "none") {
      const rect = node.getBoundingClientRect();
      const covered = Math.max(0, Math.min(rect.right, width) - Math.max(rect.left, 0)) *
        Math.max(0, Math.min(rect.bottom, height) - Math.max(rect.top, 0));
      if (covered >= area * 0.8) return node;
    }
    if (node === owner) break;
  }
  return null;
}

function staleShellState(doc, win) {
  const problems = [];
  for (const element of [doc.documentElement, doc.body, doc.getElementById("root")]) {
    if (!element) continue;
    if (element.inert || element.hasAttribute("inert")) problems.push({ element, kind: "inert" });
    if (element.getAttribute("aria-hidden") === "true") problems.push({ element, kind: "aria-hidden" });
    if (element !== doc.documentElement && win.getComputedStyle(element).pointerEvents === "none") problems.push({ element, kind: "pointer-events" });
  }
  return problems;
}

function describe(element) {
  if (!element) return "";
  const id = element.id ? `#${element.id}` : "";
  const classes = typeof element.className === "string" && element.className.trim()
    ? `.${element.className.trim().split(/\s+/).slice(0, 3).join(".")}` : "";
  return `${element.tagName.toLowerCase()}${id}${classes}`;
}

export function findStrandedOverlays({ doc = document, win = window } = {}) {
  const { width, height } = viewport(win);
  const layers = new Set();
  for (const [fx, fy] of PROBE_FRACTIONS) {
    const hit = doc.elementFromPoint(width * fx, height * fy);
    if (!hit) continue;
    const owner = portalOwner(doc, hit);
    if (!owner || hasVisibleDialog(owner, win)) continue;
    const layer = coveringLayer(owner, hit, win);
    if (layer) layers.add(layer);
  }
  return { layers: [...layers], shell: staleShellState(doc, win) };
}

export function createOverlayJanitor({
  isOverlayExpected = () => false,
  allowFullscreen = () => Boolean(globalThis.window?.ToolBox?.runtime?.supportsFullscreen),
  doc = document,
  win = window,
  log = (...args) => console.warn(...args),
  settleMs = SETTLE_MS,
} = {}) {
  let timer = null;
  let disposed = false;

  // Sheets and dialogs that live in component state (More sheet, title filter,
  // full-text adapter...) are not in any store: a visible, non-exiting dialog
  // in a body-level portal means an overlay is legitimately open.
  const dialogOpen = () => [...doc.body.children].some((child) =>
    child.id !== "root" && child.tagName !== "SCRIPT" && hasVisibleDialog(child, win));
  // The long-press menu is a body-level portal too, with a full-screen scrim
  // and no dialog role: while it is mounted, its scrim is meant to be there.
  const menuOpen = () => Boolean(doc.querySelector("body > .nextflux-context-menu"));
  const quiet = () => !isOverlayExpected() && !fullscreenElement(doc) && !doc.querySelector("dialog[open]") && !dialogOpen() && !menuOpen();

  const release = (found) => {
    const released = [];
    for (const layer of found.layers) {
      if (!layer.isConnected) continue;
      layer.style.pointerEvents = "none";
      layer.style.visibility = "hidden";
      layer.dataset.nextfluxReleased = "true";
      released.push(`overlay ${describe(layer)}`);
    }
    for (const { element, kind } of found.shell) {
      if (kind === "inert") { element.inert = false; element.removeAttribute("inert"); }
      else if (kind === "aria-hidden") element.removeAttribute("aria-hidden");
      else if (kind === "pointer-events") element.style.pointerEvents = "auto";
      released.push(`${kind} on ${describe(element)}`);
    }
    if (released.length) log("[NextFlux] released a stranded overlay:", released.join(", "));
    return released;
  };

  // Two observations, settleMs apart: a layer that is merely finishing its
  // close animation is gone by the second one.
  const check = () => new Promise((resolve) => {
    if (disposed || !quiet()) { resolve([]); return; }
    const first = findStrandedOverlays({ doc, win });
    if (!first.layers.length && !first.shell.length) { resolve([]); return; }
    win.setTimeout(() => {
      if (disposed || !quiet()) { resolve([]); return; }
      const second = findStrandedOverlays({ doc, win });
      resolve(release({
        layers: second.layers.filter((layer) => first.layers.includes(layer)),
        shell: second.shell.filter(({ element, kind }) => first.shell.some((item) => item.element === element && item.kind === kind)),
      }));
    }, settleMs);
  });

  const schedule = (delay = 300) => {
    if (disposed) return;
    if (timer) win.clearTimeout(timer);
    timer = win.setTimeout(() => { timer = null; void check(); }, delay);
  };

  // The ToolBox runtime has no custom-view fullscreen. A media element that
  // still enters fullscreen there leaves the page without input, so leave it
  // immediately unless the host declares support.
  const onFullscreenChange = () => {
    const element = fullscreenElement(doc);
    if (element && !allowFullscreen()) {
      log("[NextFlux] leaving unsupported fullscreen:", describe(element));
      const exit = doc.exitFullscreen?.bind(doc) || doc.webkitExitFullscreen?.bind(doc);
      try { Promise.resolve(exit?.()).catch(() => {}); } catch { /* already left */ }
    }
    schedule(400);
  };
  const onBack = () => {
    if (fullscreenElement(doc)) onFullscreenChange();
    schedule(400);
  };
  const onVisibility = () => { if (doc.visibilityState === "visible") schedule(400); };
  const onPointerDown = (event) => {
    // A tap that lands on a body-level layer while nothing should be open is
    // exactly the "page ignores taps" symptom; check soon.
    if (portalOwner(doc, event.target)) schedule(200);
  };
  const mutations = typeof win.MutationObserver === "function"
    ? new win.MutationObserver(() => schedule(1200)) : null;

  doc.addEventListener("fullscreenchange", onFullscreenChange);
  doc.addEventListener("webkitfullscreenchange", onFullscreenChange);
  doc.addEventListener("visibilitychange", onVisibility);
  doc.addEventListener("pointerdown", onPointerDown, { capture: true, passive: true });
  win.addEventListener("popstate", onBack);
  win.addEventListener("hashchange", onBack);
  mutations?.observe(doc.body, { childList: true });

  return {
    check,
    schedule,
    dispose() {
      disposed = true;
      if (timer) win.clearTimeout(timer);
      doc.removeEventListener("fullscreenchange", onFullscreenChange);
      doc.removeEventListener("webkitfullscreenchange", onFullscreenChange);
      doc.removeEventListener("visibilitychange", onVisibility);
      doc.removeEventListener("pointerdown", onPointerDown, { capture: true });
      win.removeEventListener("popstate", onBack);
      win.removeEventListener("hashchange", onBack);
      mutations?.disconnect();
    },
  };
}

let installed = null;
export function installOverlayJanitor(options) {
  installed?.dispose();
  installed = createOverlayJanitor(options);
  return installed;
}
