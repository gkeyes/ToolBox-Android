import "./index.css";
import { initializePreferences } from "./toolbox/preferences.js";
import { installLinkHandling } from "./toolbox/actions.js";

async function boot() {
  if (!window.ToolBox) throw new Error("请在 ToolBox 中导入并打开此小工具。");
  await window.ToolBox.ready();
  await initializePreferences();
  const { restoreAuth, authState } = await import("./stores/authStore.js");
  await restoreAuth();
  const { initializeArticleCache } = await import("./db/storage.js");
  const auth = authState.get();
  if (auth.userId) {
    await initializeArticleCache({ serverUrl: auth.serverUrl, userId: String(auth.userId) });
  }
  installLinkHandling();
  await import("./render.jsx");
}
boot().catch((error) => {
  document.getElementById("splash-screen")?.remove();
  const root = document.getElementById("root");
  const box = document.createElement("section");
  box.className = "toolbox-start-error";
  const title = document.createElement("h1");
  title.textContent = "NextFlux 暂时无法打开";
  const text = document.createElement("p");
  const cacheErrors = {
    UNSUPPORTED: "请更新 ToolBox 和 Android System WebView 后重试。",
    CACHE_INVALID: "阅读缓存无法恢复。请保留当前数据，重新打开工具后重试。",
    CACHE_COMMIT_UNCERTAIN: "缓存保存结果暂时无法确认。请重新打开工具恢复已保存的数据。",
    ACCOUNT_CHANGED: "登录信息与阅读缓存不匹配。请恢复原账号后重试。",
    CACHE_WORKER_ERROR: "阅读缓存处理未能启动。请更新 Android System WebView 后重试。",
    CACHE_WORKER_MESSAGE_ERROR: "无法读取缓存处理结果。请重新打开工具后重试。",
  };
  text.textContent = !window.ToolBox
    ? "请在 ToolBox 中导入并打开此小工具。"
    : cacheErrors[error?.code] ?? "请检查小工具的存储和安全存储权限，以及设备剩余空间，然后重试。";
  const retry = document.createElement("button");
  retry.textContent = "重试";
  retry.onclick = () => location.reload();
  box.append(title, text, retry);
  root.replaceChildren(box);
});
