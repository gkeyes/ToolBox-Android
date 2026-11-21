import { toast } from "sonner";
import { installImageShareGesture } from "./image-share-gesture.js";

function message(error, permission) {
  return error?.code === "PERMISSION_DENIED"
    ? `请在小工具权限中开启${permission}后重试。`
    : "操作未完成，请重试。";
}
export async function copyText(text) {
  try {
    await window.ToolBox.clipboard.writeText(String(text));
    toast.success("已复制");
    return true;
  } catch (error) {
    toast.error(message(error, "写入剪贴板"));
    return false;
  }
}
export async function shareText(text) {
  try {
    await window.ToolBox.share.text(String(text));
    return true;
  } catch (error) {
    toast.error(message(error, "分享"));
    return false;
  }
}

function browserErrorMessage(error) {
  switch (error?.code) {
    case "PERMISSION_DENIED": return "请在小工具权限中开启应用内浏览器后重试。";
    case "UNSUPPORTED":
    case "NOT_FOUND": return "应用内浏览器不可用，请重试或复制链接。";
    case "INVALID_SESSION":
    case "SESSION_ENDED": return "当前阅读会话已结束，请重新打开 NextFlux 后重试。";
    case "INVALID_REQUEST": return "此链接无法在应用内浏览器中打开，请检查链接地址。";
    case "SYSTEM_PERMISSION_DENIED": return "应用内浏览器无法启动，请重新打开 NextFlux 后重试。";
    default: return "应用内浏览器未能打开，请重试或复制链接。";
  }
}

export async function openInBrowser(value) {
  const url = parseLink(value);
  if (!url) return false;
  try {
    await window.ToolBox.browser.open(url.href);
    return true;
  } catch (error) {
    toast.error(browserErrorMessage(error));
    return false;
  }
}

function parseLink(value) {
  let url;
  try {
    if (typeof value !== "string" || /[\u0000-\u0020\u007f]/.test(value)) throw new Error();
    url = new URL(value);
    if (!["https:", "http:"].includes(url.protocol) || url.username || url.password) throw new Error();
  } catch {
    toast.error("此链接无法使用。");
    return null;
  }
  return url;
}

function showActionDialog({ label, heading, description, address, actions, trigger = document.activeElement, image = false }) {
  const previous = document.querySelector("dialog.toolbox-link-dialog");
  if (previous?.open) return;
  previous?.remove();
  const lifetime = new AbortController();
  const dialog = document.createElement("dialog");
  dialog.className = image ? "toolbox-link-dialog toolbox-image-dialog" : "toolbox-link-dialog";
  dialog.setAttribute("aria-label", label);
  const title = document.createElement("h2");
  title.textContent = heading;
  dialog.append(title);
  if (description) {
    const explanation = document.createElement("p");
    explanation.id = "toolbox-link-explanation";
    explanation.textContent = description;
    dialog.setAttribute("aria-describedby", explanation.id);
    dialog.append(explanation);
  }
  if (address) {
    const details = document.createElement("p");
    details.className = "toolbox-link-address";
    details.textContent = address;
    dialog.append(details);
  }
  const failure = image ? document.createElement("p") : null;
  if (failure) {
    failure.hidden = true;
    failure.setAttribute("role", "alert");
    dialog.append(failure);
  }
  const reportFailure = text => {
    failure.textContent = text;
    failure.hidden = false;
  };
  const footer = document.createElement("div");
  footer.className = "toolbox-link-actions";
  let pending = false;
  function dismiss() {
    lifetime.abort();
    if (dialog.open) dialog.close();
  }

  for (const { label, primary, action, cancel } of [...actions, { label: "取消", cancel: true }]) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = label;
    button.className = primary ? "toolbox-link-primary" : "toolbox-link-secondary";
    button.onclick = async () => {
      if (cancel && (!pending || image)) { dismiss(); return; }
      if (pending) return;
      if (failure) failure.hidden = true;
      pending = true;
      dialog.setAttribute("aria-busy", "true");
      for (const control of footer.children) {
        control.disabled = !(image && control === footer.lastElementChild);
      }
      try {
        if (await action(lifetime.signal, reportFailure)) dismiss();
      } finally {
        pending = false;
        dialog.removeAttribute("aria-busy");
        for (const control of footer.children) control.disabled = false;
        if (dialog.isConnected && dialog.open) button.focus();
      }
    };
    footer.append(button);
  }
  dialog.append(footer);
  // Only a gesture starting and ending on the backdrop dismisses the dialog.
  // Dragging from the address or a button must not accidentally cancel it.
  const outside = (event) => {
    const bounds = dialog.getBoundingClientRect();
    return event.clientX < bounds.left || event.clientX > bounds.right
      || event.clientY < bounds.top || event.clientY > bounds.bottom;
  };
  let backdropPress = false;
  dialog.addEventListener("pointerdown", (event) => {
    backdropPress = event.target === dialog && outside(event);
  });
  dialog.addEventListener("pointercancel", () => { backdropPress = false; });
  // Some Android WebViews send touch and click but no pointer events.
  dialog.addEventListener("touchstart", (event) => {
    const touch = event.touches[0];
    backdropPress = event.target === dialog && !!touch && outside(touch);
  }, { passive: true });
  dialog.addEventListener("touchcancel", () => { backdropPress = false; });
  dialog.addEventListener("click", (event) => {
    if ((!pending || image) && backdropPress && event.target === dialog && outside(event)) dismiss();
    backdropPress = false;
  });
  dialog.addEventListener("cancel", (event) => {
    if (pending && !image) event.preventDefault();
    else lifetime.abort();
  });
  // Keep native dialog keyboard behavior, but don't also run reader shortcuts.
  dialog.addEventListener("keydown", (event) => event.stopPropagation());
  dialog.addEventListener("close", () => {
    lifetime.abort();
    dialog.remove();
    if (!document.querySelector("dialog[open]") && trigger?.isConnected) trigger.focus({ preventScroll: true });
  }, { once: true });
  document.body.append(dialog);
  dialog.showModal();
  footer.firstElementChild.focus({ preventScroll: true });
  if (image) window.addEventListener("hashchange", dismiss, { signal: lifetime.signal });
}

export function showLinkActions(value) {
  const url = parseLink(value);
  if (!url) return;
  showActionDialog({
    label: "链接操作",
    heading: "打开原文链接",
    description: "在 ToolBox 内打开网页，也可以复制或分享链接。",
    address: url.href,
    actions: [
      { label: "应用内打开", primary: true, action: () => openInBrowser(url.href) },
      { label: "复制链接", action: () => copyText(url.href) },
      { label: "分享链接", action: () => shareText(url.href) },
    ],
  });
}

function showImageActions(image) {
  const shown = image.currentSrc || image.src;
  const link = image.dataset.nextfluxImageSource;
  const share = window.ToolBox?.share;
  const canShareFile = typeof share?.image === "function" && Boolean(image.dataset.nextfluxImageShare);
  if (!canShareFile && (!link || typeof share?.text !== "function")) {
    toast.error(typeof share?.image === "function" ? "当前图片格式无法分享。" : "请更新 ToolBox 后分享图片文件。");
    return;
  }
  showActionDialog({
    label: "图片操作",
    heading: "图片操作",
    image: true,
    trigger: image,
    actions: [{
      label: canShareFile ? "分享图片" : "分享图片链接",
      primary: true,
      action: canShareFile ? async (signal, reportFailure) => {
        try {
          const { readShareableImage } = await import("./media.js");
          if (signal.aborted) return false;
          if (!image.isConnected || (image.currentSrc || image.src) !== shown) {
            reportFailure("图片已经变化，请重新长按。");
            return false;
          }
          const blob = await readShareableImage(shown, signal);
          if (signal.aborted) return false;
          await share.image(blob, { signal });
          return true;
        } catch (error) {
          if (signal.aborted || error?.code === "CANCELLED" || error?.name === "AbortError") return false;
          const detail = {
            INVALID_MIME: "当前图片格式无法分享。",
            QUOTA_EXCEEDED: "图片超过 32 MB 的分享上限。",
            UNSUPPORTED: "请更新 ToolBox 后分享图片文件。",
          };
          reportFailure(detail[error?.code] || message(error, "分享"));
          return false;
        }
      } : () => shareText(link),
    }],
  });
}

export function installLinkHandling() {
  const imageGestures = installImageShareGesture(showImageActions);
  document.addEventListener("click", event => {
    if (imageGestures.consumedClick(event)) return;
    const anchor = event.target.closest?.("a[href]");
    if (!anchor) return;
    // Only the safe reader renderer sets this marker; article HTML cannot
    // supply it. Its root handles targets that may still be arriving in a batch.
    if (anchor.hasAttribute("data-reading-local-anchor") && anchor.closest("[data-font-reading-root]")) return;
    const target = new URL(anchor.href, location.href);
    if (target.origin === location.origin && target.pathname === location.pathname && target.search === location.search && target.hash) return;
    event.preventDefault();
    event.stopPropagation();
    void openInBrowser(target.href);
  }, true);
  document.addEventListener("contextmenu", event => {
    if (imageGestures.consumedMenu(event)) return;
    const anchor = event.target.closest?.("a[href]");
    if (!anchor) return;
    if (anchor.hasAttribute("data-reading-local-anchor") && anchor.closest("[data-font-reading-root]")) return;
    const target = new URL(anchor.href, location.href);
    if (target.origin === location.origin && target.pathname === location.pathname && target.search === location.search && target.hash) return;
    event.preventDefault();
    event.stopPropagation();
    showLinkActions(target.href);
  }, true);
  window.addEventListener("nextflux:storage-error", () => toast.error("保存失败，请检查存储权限或可用空间。"));
}
