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
export function showLinkActions(value) {
  let url;
  try {
    url = new URL(value);
    if (!["https:", "http:"].includes(url.protocol) || url.username || url.password) throw new Error();
  } catch {
    toast.error("此链接无法使用。");
    return;
  }
  document.querySelector("dialog.toolbox-link-dialog")?.remove();
  const dialog = document.createElement("dialog");
  dialog.className = "toolbox-link-dialog";
  dialog.setAttribute("aria-label", "链接操作");
  const title = document.createElement("h2");
  title.textContent = "打开原文链接";
  const explanation = document.createElement("p");
  explanation.textContent = "可复制到浏览器，或通过系统分享打开。";
  const address = document.createElement("p");
  address.className = "toolbox-link-address";
  address.textContent = url.href;
  const footer = document.createElement("div");
  for (const [label, action] of [
    ["复制链接", () => copyText(url.href)],
    ["分享链接", () => shareText(url.href)],
    ["取消", async () => true],
  ]) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = label;
    button.onclick = async () => { if (await action()) dialog.close(); };
    footer.append(button);
  }
  dialog.append(title, explanation, address, footer);
  dialog.addEventListener("close", () => dialog.remove(), { once: true });
  document.body.append(dialog);
  dialog.showModal();
}
export function installLinkHandling() {
  document.addEventListener("click", event => {
    const anchor = event.target.closest?.("a[href]");
    if (!anchor) return;
    const target = new URL(anchor.href, location.href);
    if (target.origin === location.origin && target.pathname === location.pathname && target.hash) return;
    event.preventDefault();
    event.stopPropagation();
    showLinkActions(target.href);
  }, true);
  window.addEventListener("nextflux:storage-error", () => toast.error("保存失败，请检查存储权限或可用空间。"));
  window.addEventListener("nextflux:cache-evicted", () => toast.info("本地缓存已满，已保留较新的文章。服务器中的文章不受影响。"));
}
