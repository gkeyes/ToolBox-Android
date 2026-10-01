import "./index.css";
import "./compact-controls.css";
import { initializePreferences, flushPreferences } from "./toolbox/preferences.js";
import { installLinkHandling } from "./toolbox/actions.js";
import { runtimeForeground, runtimeClosing } from "./toolbox/foreground.js";

let startupStage = "连接 ToolBox";

async function boot() {
  if (!window.ToolBox) throw new Error("请在 ToolBox 中导入并打开此小工具。");
  await window.ToolBox.ready();
  window.ToolBox.runtime.onStateChanged((snapshot) => {
    runtimeClosing.set(snapshot.closing);
    runtimeForeground.set(snapshot.foreground && !snapshot.closing);
  });
  window.ToolBox.runtime.registerFlushHandler(async () => {
    // Stores must read the restored preference engine, including during early exit.
    await initializePreferences();
    const [{ flushSyncWork }, { flushBackgroundWork }] = await Promise.all([
      import("./stores/syncStore.js"), import("./toolbox/background.js"),
    ]);
    const work = await Promise.allSettled([flushSyncWork(), flushBackgroundWork()]);
    await flushPreferences();
    const failure = work.find((result) => result.status === "rejected");
    if (failure) throw failure.reason;
  });
  startupStage = "恢复设置";
  await initializePreferences();
  startupStage = "恢复登录信息";
  const { restoreAuth, authState } = await import("./stores/authStore.js");
  await restoreAuth();
  const { initializeArticleCache } = await import("./db/storage.js");
  const auth = authState.get();
  if (auth.userId) {
    startupStage = "恢复阅读缓存";
    await initializeArticleCache({ serverUrl: auth.serverUrl, userId: String(auth.userId) });
  }
  startupStage = "打开阅读界面";
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
    UNSUPPORTED: "当前运行环境不支持阅读缓存功能；请检查 Android System WebView 后重试。",
    CACHE_INVALID: "阅读缓存无法恢复。请保留当前数据，重新打开工具后重试。",
    CACHE_COMMIT_UNCERTAIN: "缓存保存结果暂时无法确认。请重新打开工具恢复已保存的数据。",
    ACCOUNT_CHANGED: "登录信息与阅读缓存不匹配。请恢复原账号后重试。",
    CACHE_WORKER_ERROR: "阅读缓存处理未能启动。请更新 Android System WebView 后重试。",
    CACHE_WORKER_MESSAGE_ERROR: "无法读取缓存处理结果。请重新打开工具后重试。",
  };
  const safeCodes = new Set([
    ...Object.keys(cacheErrors), "INVALID_REQUEST", "INVALID_SESSION", "WRONG_ORIGIN", "NOT_MAIN_FRAME",
    "NOT_DECLARED", "PERMISSION_DENIED", "SYSTEM_PERMISSION_DENIED", "BUSY", "QUOTA_EXCEEDED",
    "CANCELLED", "SESSION_ENDED", "NOT_FOUND", "DUPLICATE_TASK", "NETWORK_BLOCKED",
    "NETWORK_UNAVAILABLE", "NETWORK_TIMEOUT", "INTERNAL_ERROR",
  ]);
  const safeNames = new Set(["TypeError", "RangeError", "SyntaxError", "SecurityError", "NotSupportedError", "QuotaExceededError"]);
  const code = safeCodes.has(error?.code) ? error.code : safeNames.has(error?.name) ? error.name : "UNKNOWN";
  let advice = "请保留现有数据，重新打开工具后重试。";
  if (code === "PERMISSION_DENIED") {
    advice = startupStage === "恢复登录信息"
      ? "请在此工具的权限页面开启安全存储后重试。"
      : "请在此工具的权限页面检查存储和安全存储权限后重试。";
  } else if (code === "SYSTEM_PERMISSION_DENIED") {
    advice = "请在系统设置中检查 ToolBox 的相关权限后重试。";
  } else if (code === "NOT_DECLARED" || (code === "UNSUPPORTED" && startupStage !== "恢复阅读缓存")) {
    advice = "当前工具与宿主的能力不匹配，请更新 ToolBox 和此工具后重试。";
  } else if (code === "QUOTA_EXCEEDED" || code === "QuotaExceededError") {
    advice = "可用存储额度不足，请检查此工具的存储用量后重试。";
  } else if (startupStage === "恢复阅读缓存" && cacheErrors[code]) {
    advice = cacheErrors[code];
  }
  text.textContent = !window.ToolBox
    ? "请在 ToolBox 中导入并打开此小工具。"
    : `${startupStage}失败（${code}）。${advice}`;
  const retry = document.createElement("button");
  retry.textContent = "重试";
  retry.onclick = () => location.reload();
  box.append(title, text, retry);
  root.replaceChildren(box);
});
