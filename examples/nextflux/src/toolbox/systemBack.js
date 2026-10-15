// System back (the phone's back key or edge swipe) is offered to the page
// first: the ToolBox host dispatches a cancelable `toolboxback` event on the
// main window, and preventDefault() keeps it from reaching the host's
// "press again to exit". The answer must be given synchronously.
//
// Back closes the top layer the same way its own dismiss gesture would, so
// the existing close paths (and their animations, and their refusal while a
// save is pending) all apply. With nothing to close, the host handles it.

import { hasVisibleDialog } from "@/toolbox/overlayJanitor.js";

const subpages = [];

// A subpage (the reader) registers how to leave it while it is open.
export function registerBackHandler(handler) {
  subpages.push(handler);
  return () => {
    const index = subpages.lastIndexOf(handler);
    if (index >= 0) subpages.splice(index, 1);
  };
}

function pressEscape(target) {
  const init = { key: "Escape", code: "Escape", keyCode: 27, which: 27, bubbles: true, cancelable: true };
  target.dispatchEvent(new KeyboardEvent("keydown", init));
  target.dispatchEvent(new KeyboardEvent("keyup", init));
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
    const cancel = new Event("cancel", { cancelable: true });
    if (native.dispatchEvent(cancel)) native.close();
    return true;
  }

  // Body-level portals, newest (topmost) first: the photo gallery, the
  // long-press menu, and React Aria sheets, alerts and popovers.
  for (const layer of [...doc.body.children].reverse()) {
    if (layer.id === "root" || layer.tagName === "SCRIPT" || layer.dataset.nextfluxReleased) continue;
    if (layer.matches(".PhotoView-Portal") || layer.querySelector(".PhotoView-Portal")) {
      pressEscape(win);
      return true;
    }
    if (layer.matches(".nextflux-context-menu") && layer.childElementCount && visible(layer, win)) {
      pressEscape(doc);
      return true;
    }
    if (hasVisibleDialog(layer, win)) {
      const dialog = [...layer.querySelectorAll("[role='dialog'], [role='alertdialog']")]
        .filter((element) => !element.closest("[data-exiting='true']")).pop() || layer;
      // Alerts ignore Escape; back is their close (×) button. While it is
      // disabled (a request is running) back stays on the alert.
      if (dialog.getAttribute("role") === "alertdialog") {
        const close = dialog.querySelector("[slot='close'], .nextflux-close-button");
        if (close && !close.disabled && close.getAttribute("aria-disabled") !== "true") close.click();
      } else {
        pressEscape(dialog);
      }
      return true;
    }
  }

  const subpage = subpages[subpages.length - 1];
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
