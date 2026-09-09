import { atom } from "nanostores";
import { SERVER_URL, assertServerUrl, basicAuth, request, responseText, httpError } from "../toolbox/network.js";
import { initializeArticleCache, invalidateArticleReads } from "../db/storage.js";

const AUTH_KEY = "nextflux.auth";
const defaultValue = {
  serverUrl: SERVER_URL,
  username: "",
  password: "",
  userId: "",
  token: "",
  authType: "token",
};

// Credentials are memory-only here; persistent storage is Android Keystore-backed.
export const authState = atom({ ...defaultValue });

function secureStorage() {
  const secure = window.ToolBox?.storage?.secure;
  if (!secure) throw new Error("请在 ToolBox 中打开，并开启安全存储权限。");
  return secure;
}

export async function restoreAuth() {
  authState.set({ ...defaultValue });
  let stored;
  try {
    stored = await secureStorage().get(AUTH_KEY);
  } catch {
    throw new Error("无法读取登录信息，请检查小工具的安全存储权限。");
  }
  if (!stored || typeof stored !== "object") return;
  if (stored.serverUrl !== SERVER_URL || !stored.userId ||
      (stored.authType !== "token" && stored.authType !== "basic")) return;
  if (stored.authType === "token" ? typeof stored.token !== "string" || !stored.token :
      typeof stored.username !== "string" || typeof stored.password !== "string" || !stored.password) return;
  authState.set({
    ...defaultValue,
    serverUrl: SERVER_URL,
    username: typeof stored.username === "string" ? stored.username : "",
    userId: stored.userId,
    authType: stored.authType,
    token: stored.authType === "token" ? stored.token : "",
    password: stored.authType === "basic" ? stored.password : "",
  });
}

let logoutPending = null;

export async function login(serverUrl, username, password, token) {
  if (logoutPending) await logoutPending;
  const normalized = assertServerUrl(serverUrl);
  if (normalized.pathname !== "/" || normalized.search || normalized.hash) {
    throw new Error("请使用预设服务器地址。");
  }
  const headers = token ? { "X-Auth-Token": token } : { Authorization: basicAuth(username, password) };
  const response = await request(`${SERVER_URL}/v1/me`, { headers });
  if (response.status < 200 || response.status >= 300) throw httpError(response.status);
  let user;
  try { user = JSON.parse(responseText(response)); } catch { throw new Error("服务器未返回有效的用户信息。"); }
  if (!user?.id || typeof user.username !== "string") throw new Error("服务器未返回有效的用户信息。");
  const nextAuth = {
    serverUrl: SERVER_URL,
    username: user.username,
    password: token ? "" : password,
    token: token || "",
    authType: token ? "token" : "basic",
    userId: user.id,
  };
  // A failed logout may have retained another account's cache. Verify its
  // binding before persisting or publishing the newly authenticated account.
  await initializeArticleCache({ serverUrl: SERVER_URL, userId: String(user.id) });
  try {
    await secureStorage().set(AUTH_KEY, nextAuth);
  } catch {
    throw new Error("无法安全保存登录信息，请开启安全存储权限后重试。");
  }
  authState.set(nextAuth);
  return user;
}

export function logout() {
  if (logoutPending) return logoutPending;
  invalidateArticleReads();
  authState.set({ ...defaultValue });
  logoutPending = (async () => {
    const { clearMediaCache } = await import("../toolbox/media.js");
    clearMediaCache();
    const { cancelAccountOperations, lastSync } = await import("./syncStore.js");
    const draining = cancelAccountOperations();
    const { stopContinuousSync } = await import("../toolbox/background.js");
    let backgroundFailure = null;
    try { await stopContinuousSync(); } catch (failure) { backgroundFailure = failure; }
    await draining;
    const [{ clearArticleCache }, articles, feeds] = await Promise.all([
      import("../db/storage.js"), import("./articlesStore.js"), import("./feedsStore.js"),
    ]);
    await Promise.all([secureStorage().remove(AUTH_KEY), clearArticleCache()]);
    articles.resetArticleState();
    feeds.feeds.set([]);
    feeds.categories.set([]);
    feeds.unreadCounts.set({});
    feeds.starredCounts.set({});
    lastSync.set(null);
    if (backgroundFailure) throw new Error("已退出登录，但停止后台同步失败，请在 ToolBox 中停止此小工具。");
  })().finally(() => { logoutPending = null; });
  return logoutPending;
}
