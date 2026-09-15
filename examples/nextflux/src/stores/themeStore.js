import { persistentAtom } from "@nanostores/persistent";
import "../toolbox/preferences.js";
import { atom, computed } from "nanostores";

const defaultValue = {
  themeMode: "system",
  lightTheme: "light",
  darkTheme: "dark",
};

// 主题配置
export const themes = {
  light: [
    { id: "light", name: "白色", color: "#ffffff" },
    { id: "stone", name: "石灰", color: "#F3F1ED" },
    { id: "leaf", name: "leaf", color: "#c8e6c9" },
  ],
  dark: [
    { id: "dark", name: "黑色", color: "#1E1E1E" },
    { id: "nord-dark", name: "深蓝", color: "#4c566a" },
  ],
};

export const themeState = persistentAtom("theme", defaultValue, {
  encode: JSON.stringify,
  decode: (str) => {
    const storedValue = JSON.parse(str);
    return { ...defaultValue, ...storedValue };
  },
});

// Keep the live media value reactive as well as the persisted user preference.
// ToolBox supplies the native WebView color scheme; no host-specific JS API is required.
const systemDark = atom(window.matchMedia("(prefers-color-scheme: dark)").matches);
export const currentThemeMode = computed([themeState, systemDark], (state, dark) =>
  state.themeMode === "system" ? (dark ? "dark" : "light") : state.themeMode,
);

function applyTheme() {
  const state = themeState.get();
  const mode = currentThemeMode.get();
  const root = window.document.documentElement;
  const selected = mode === "dark" ? state.darkTheme : state.lightTheme;
  root.classList.remove(...[...themes.light, ...themes.dark].map((theme) => theme.id));
  root.classList.add(mode);
  root.setAttribute("data-theme", selected);
}

export function setTheme(mode, themeId = null) {
  if (!["system", "light", "dark"].includes(mode)) return;
  const state = { ...themeState.get(), themeMode: mode };
  if (themeId && mode !== "system" && themes[mode].some((theme) => theme.id === themeId)) {
    state[mode === "dark" ? "darkTheme" : "lightTheme"] = themeId;
  }
  themeState.set(state);
  applyTheme();
}

let disposeTheme;
export function initTheme() {
  disposeTheme?.();
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  systemDark.set(media.matches);
  const onChange = (event) => { systemDark.set(event.matches); applyTheme(); };
  media.addEventListener("change", onChange);
  // Includes hydrated preferences and changes made from another component.
  const unlisten = themeState.subscribe(applyTheme);
  const dispose = () => {
    media.removeEventListener("change", onChange);
    unlisten();
    if (disposeTheme === dispose) disposeTheme = undefined;
  };
  disposeTheme = dispose;
  return dispose;
}
