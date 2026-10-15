// System back (the phone's back key or edge swipe) is offered to the page
// first: the ToolBox host dispatches a cancelable `toolboxback` event on the
// main window, and preventDefault() keeps it from reaching the host's
// "press again to exit". The answer must be given synchronously.
//
// Back closes the top layer the same way its own dismiss gesture would, so
// the existing close paths and their animations apply. With nothing to close,
// or with a top layer that is busy and refuses to close, the host handles it.

import { hasVisibleDialog } from "@/toolbox/overlayJanitor.js";

const subpages = [];

// A subpage (the reader, the phone sidebar) registers how to leave it while it
// is open. `layer` says which one is drawn on top: a handler re-registers when
// its route changes, so the order of registration alone is not the stacking.
export function registerBackHandler(handler, layer = 0) {
  const entry = { handler, layer };
  subpages.push(entry);
  return () => {
    const index = subpages.lastIndexOf(entry);
    if (index >= 0) subpages.splice(index, 1);
  };
}

function topSubpage() {
  let top = null;
  for (const entry of subpages) if (!top || entry.layer >= top.layer) top = entry;
  return top?.handler;
}

// The Escape is meant for the top layer only. It stops at the document so
// the reader's window-level Escape shortcut (close the article) never sees it.
function pressEscape(target, doc) {
  const init = { key: "Escape", code: "Escape", keyCode: 27, which: 27, bubbles: true, cancelable: true };
  for (const type of ["keydown", "keyup"]) {
    const event = new KeyboardEvent(type, init);
    const contain = (seen) => { if (seen === event) seen.stopPropagation(); };
    doc.addEventListener(type, contain);
    try { target.dispatchEvent(event); } finally { doc.removeEventListener(type, contain); }
  }
}

function visible(element, win) {
  const rect = element.getBoundingClientRect();
  return rect.width > 0 && rect.height > 0 && win.getComputedStyle(element).visibility !== "hidden";
}

export function closeTopLayer({ doc = document, win = window } = {}) {
  const fullscreen = doc.fullscreenElement || doc.webkitFullscreenElement;
  if (fullscreen) {
    const exit = doc.exitFullscreen?.bind(doc) || doc.webkitExitFullscreen?.bind(doc);
    try { Promise.resolve(exit?.()).catch(() => {}); } catch { /* already left */ }
    return true;
  }

  // Native modal dialogs (the link dialog) sit in the top layer, above all.
  const native = [...doc.querySelectorAll("dialog[open]")].pop();
  if (native) {
    // A dialog that refuses (the link dialog while a share is answering) is
    // busy: back goes to the host rather than being swallowed.
    const cancel = new Event("cancel", { cancelable: true });
    if (!native.dispatchEvent(cancel)) return false;
    native.close();
    return true;
  }

  // Body-level portals, newest (topmost) first: the photo gallery, the
  // long-press menu, and React Aria sheets, alerts and popovers.
  for (const layer of [...doc.body.children].reverse()) {
    if (layer.id === "root" || layer.tagName === "SCRIPT" || layer.dataset.nextfluxReleased) continue;
    // A layer left behind hidden and click-through (galleryRelease.js, the
    // overlay janitor) is not open.
    const style = win.getComputedStyle(layer);
    if (style.visibility === "hidden" || style.pointerEvents === "none") continue;
    // The gallery listens on window and the reader's shortcut skips Escape
    // while it is open. A portal left hidden by galleryRelease.js is not open.
    const gallery = layer.matches(".PhotoView-Portal") ? layer : layer.querySelector(".PhotoView-Portal");
    if (gallery && visible(gallery, win) && win.getComputedStyle(gallery).pointerEvents !== "none") {
      win.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", code: "Escape", keyCode: 27, which: 27 }));
      return true;
    }
    if (layer.matches(".nextflux-context-menu") && layer.childElementCount && visible(layer, win)) {
      pressEscape(doc, doc);
      return true;
    }
    // Dropdown and select popovers hold a menu or listbox, not a dialog.
    const list = [...layer.querySelectorAll("[role='menu'], [role='listbox']")]
      .filter((element) => !element.closest("[data-exiting='true']") && visible(element, win)).pop();
    if (list && !hasVisibleDialog(layer, win)) {
      pressEscape(list, doc);
      return true;
    }
    if (hasVisibleDialog(layer, win)) {
      const dialog = [...layer.querySelectorAll("[role='dialog'], [role='alertdialog']")]
        .filter((element) => !element.closest("[data-exiting='true']")).pop() || layer;
      // A layer that refuses to close while a request runs is busy: back is
      // left to the host (its exit hint), so a stalled request never traps
      // the reader. Alerts are busy when their × is disabled; sheets say so
      // with data-nf-locked (CustomModal's locked prop).
      if (dialog.closest("[data-nf-locked='true']") || dialog.querySelector("[data-nf-locked='true']")) return false;
      // Alerts ignore Escape; back is their close (×) button.
      if (dialog.getAttribute("role") === "alertdialog") {
        const close = dialog.querySelector("[slot='close'], .nextflux-close-button");
        if (!close || close.disabled || close.getAttribute("aria-disabled") === "true") return false;
        close.click();
      } else {
        pressEscape(dialog, doc);
      }
      return true;
    }
  }

  const subpage = topSubpage();
  if (subpage) {
    subpage();
    return true;
  }
  return false;
}

export function installSystemBack({ win = window } = {}) {
  const onBack = (event) => {
    if (closeTopLayer({ doc: win.document, win })) event.preventDefault();
  };
  win.addEventListener("toolboxback", onBack);
  return () => win.removeEventListener("toolboxback", onBack);
}
