import { toast } from "sonner";

function message(error, permission) {
  return error?.code === "PERMISSION_DENIED"
    ? `请在小工具权限中开启${permission}后重试。`
    : error?.code === "USER_GESTURE_REQUIRED"
      ? "请点击按钮后重试。"
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
    case "USER_GESTURE_REQUIRED": return "请点击链接或“应用内打开”后重试。";
    case "UNSUPPORTED":
    case "NOT_FOUND": return "应用内浏览器不可用，请将 ToolBox 升级至 0.7.8 或更新版本后重试。";
    case "INVALID_SESSION":
    case "SESSION_ENDED": return "当前阅读会话已结束，请重新打开 NextFlux 后重试。";
    case "RATE_LIMITED": return "打开链接过于频繁，请稍后重试。";
    case "INVALID_REQUEST": return "此链接无法在应用内浏览器中打开，请检查链接地址。";
    case "SYSTEM_PERMISSION_DENIED": return "应用内浏览器无法启动，请重新打开 NextFlux 后重试。";
    default: return "应用内浏览器未能打开，请重试或复制链接。";
  }
}

export async function openInBrowser(value) {
  const url = parseLink(value);
  if (!url) return false;
  if (!window.ToolBox?.browser?.open) {
    toast.error("请先将 ToolBox 升级至 0.7.8 或更新版本，再打开链接。");
    return false;
  }
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

export function showLinkActions(value) {
  const url = parseLink(value);
  if (!url) return;
  document.querySelector("dialog.toolbox-link-dialog")?.remove();
  const trigger = document.activeElement;
  const dialog = document.createElement("dialog");
  dialog.className = "toolbox-link-dialog";
  dialog.setAttribute("aria-label", "链接操作");
  dialog.setAttribute("aria-describedby", "toolbox-link-explanation");
  const title = document.createElement("h2");
  title.textContent = "打开原文链接";
  const explanation = document.createElement("p");
  explanation.id = "toolbox-link-explanation";
  explanation.textContent = "在 ToolBox 内打开网页，也可以复制或分享链接。";
  const address = document.createElement("p");
  address.className = "toolbox-link-address";
  address.textContent = url.href;
  const footer = document.createElement("div");
  footer.className = "toolbox-link-actions";
  let pending = false;
  for (const [label, action] of [
    ["应用内打开", () => openInBrowser(url.href)],
    ["复制链接", () => copyText(url.href)],
    ["分享链接", () => shareText(url.href)],
    ["取消", async () => true],
  ]) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = label;
    button.className = label === "应用内打开" ? "toolbox-link-primary" : "toolbox-link-secondary";
    button.onclick = async () => {
      if (pending) return;
      pending = true;
      dialog.setAttribute("aria-busy", "true");
      for (const control of footer.children) control.disabled = true;
      try {
        if (await action()) dialog.close();
      } finally {
        pending = false;
        dialog.removeAttribute("aria-busy");
        for (const control of footer.children) control.disabled = false;
        if (dialog.isConnected && dialog.open) button.focus();
      }
    };
    footer.append(button);
  }
  dialog.append(title, explanation, address, footer);
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
  dialog.addEventListener("click", (event) => {
    if (!pending && backdropPress && event.target === dialog && outside(event)) dialog.close();
    backdropPress = false;
  });
  dialog.addEventListener("cancel", (event) => {
    if (pending) event.preventDefault();
  });
  // Keep native dialog keyboard behavior, but don't also run reader shortcuts.
  dialog.addEventListener("keydown", (event) => event.stopPropagation());
  dialog.addEventListener("close", () => {
    dialog.remove();
    if (!document.querySelector("dialog[open]") && trigger?.isConnected) trigger.focus({ preventScroll: true });
  }, { once: true });
  document.body.append(dialog);
  dialog.showModal();
  footer.firstElementChild.focus({ preventScroll: true });
}
export function installLinkHandling() {
  document.addEventListener("click", event => {
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
