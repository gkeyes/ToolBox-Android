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
    case "PERMISSION_DENIED": return "请在小工具权限中开启浏览器打开后重试。";
    case "USER_GESTURE_REQUIRED": return "请点击“浏览器打开”后重试。";
    case "UNSUPPORTED":
    case "NOT_FOUND": return "未找到可用浏览器，请安装或启用浏览器后重试。";
    case "INVALID_SESSION":
    case "SESSION_ENDED": return "当前阅读会话已结束，请重新打开 NextFlux 后重试。";
    case "RATE_LIMITED": return "打开链接过于频繁，请稍后重试。";
    case "INVALID_REQUEST": return "此链接无法在浏览器中打开，请检查链接地址。";
    case "SYSTEM_PERMISSION_DENIED": return "系统阻止了浏览器启动，请检查浏览器是否已启用后重试。";
    default: return "浏览器未能打开，请重试或复制链接。";
  }
}

export async function openInBrowser(url) {
  if (!window.ToolBox?.browser?.open) {
    toast.error("请先将 ToolBox 升级至 0.6.7 或更新版本，再打开链接。");
    return false;
  }
  try {
    await window.ToolBox.browser.open(url);
    return true;
  } catch (error) {
    toast.error(browserErrorMessage(error));
    return false;
  }
}

export function showLinkActions(value) {
  let url;
  try {
    if (typeof value !== "string" || value.length > 2048 || /[\u0000-\u0020\u007f]/.test(value)) throw new Error();
    url = new URL(value);
    if (!["https:", "http:"].includes(url.protocol) || url.username || url.password || url.href.length > 2048) throw new Error();
  } catch {
    toast.error("此链接无法使用。");
    return;
  }
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
  explanation.textContent = "将由系统浏览器处理；你也可以先复制或分享链接。";
  const address = document.createElement("p");
  address.className = "toolbox-link-address";
  address.textContent = url.href;
  const footer = document.createElement("div");
  footer.className = "toolbox-link-actions";
  let pending = false;
  for (const [label, action] of [
    ["浏览器打开", () => openInBrowser(url.href)],
    ["复制链接", () => copyText(url.href)],
    ["分享链接", () => shareText(url.href)],
    ["取消", async () => true],
  ]) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = label;
    button.className = label === "浏览器打开" ? "toolbox-link-primary" : "toolbox-link-secondary";
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
    if (target.origin === location.origin && target.pathname === location.pathname && target.hash) return;
    event.preventDefault();
    event.stopPropagation();
    showLinkActions(target.href);
  }, true);
  window.addEventListener("nextflux:storage-error", () => toast.error("保存失败，请检查存储权限或可用空间。"));
}
