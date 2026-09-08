import "./index.css";
import { initializePreferences } from "./toolbox/preferences.js";
import { installLinkHandling } from "./toolbox/actions.js";

async function boot() {
  if (!window.ToolBox) throw new Error("请在 ToolBox 中导入并打开此小工具。");
  await window.ToolBox.ready();
  await initializePreferences();
  const { initializeArticleCache } = await import("./db/storage.js");
  await initializeArticleCache();
  const { restoreAuth } = await import("./stores/authStore.js");
  await restoreAuth();
  installLinkHandling();
  await import("./render.jsx");
}
boot().catch(() => {
  document.getElementById("splash-screen")?.remove();
  const root = document.getElementById("root");
  const box = document.createElement("section");
  box.className = "toolbox-start-error";
  const title = document.createElement("h1");
  title.textContent = "NextFlux 暂时无法打开";
  const text = document.createElement("p");
  text.textContent = window.ToolBox
    ? "请在 ToolBox 的小工具权限中开启存储和安全存储，然后重试。"
    : "请在 ToolBox 中导入并打开此小工具。";
  const retry = document.createElement("button");
  retry.textContent = "重试";
  retry.onclick = () => location.reload();
  box.append(title, text, retry);
  root.replaceChildren(box);
});
