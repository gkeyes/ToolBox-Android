import { createRoot } from "react-dom/client";
import { HashRouter } from "react-router-dom";
import { Toaster } from "sonner";
import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import zhCN from "../../../src/i18n/locales/zh-CN.js";
import { initializePreferences } from "@/toolbox/preferences.js";
import { feedLoads } from "./controls-sidebar-state.js";
import "../../../src/index.css";

const storageKey = "nextflux-sidebar-test-preferences";
let failure = false;
let hold = false;
const pending = [];
const writes = [];
const storage = {
  async get() { return JSON.parse(localStorage.getItem(storageKey) || "null"); },
  async set(_, value) {
    const showHiddenFeeds = JSON.parse(value.settings).showHiddenFeeds;
    writes.push(showHiddenFeeds);
    if (failure) { failure = false; throw new Error("Fixture preference storage unavailable"); }
    if (hold) await new Promise((resolve) => pending.push(resolve));
    localStorage.setItem(storageKey, JSON.stringify(value));
  },
  secure: { async get() { return null; }, async set() {}, async remove() {} },
};
await initializePreferences({ storage });
const { default: FeedListSidebar } = await import("@/components/FeedList/FeedListSidebar.jsx");
const { default: General } = await import("@/components/Settings/General.jsx");
const { SidebarProvider, SidebarTrigger } = await import("@/components/ui/sidebar.jsx");
const { settingsState } = await import("@/stores/settingsStore.js");
await i18n.use(initReactI18next).init({
  lng: "zh-CN", fallbackLng: "zh-CN", resources: { "zh-CN": { translation: zhCN } },
  interpolation: { escapeValue: false },
});
document.documentElement.dataset.theme = "light";

window.controlsSidebarFixture = {
  settings: () => settingsState.get(),
  feedLoads,
  writes,
  pendingWrites: () => pending.length,
  failNextWrite: () => { failure = true; },
  holdWrites: () => { hold = true; },
  finishWrites: () => { hold = false; for (const resolve of pending.splice(0)) resolve(); },
  persistedHidden: () => {
    const stored = JSON.parse(localStorage.getItem(storageKey) || "null");
    return stored?.settings ? JSON.parse(stored.settings).showHiddenFeeds : null;
  },
};

createRoot(document.getElementById("root")).render(<HashRouter>
  <SidebarProvider>
    <FeedListSidebar />
    <main className="flex-1 min-w-0 p-5 md:ml-80">
      <SidebarTrigger />
      <section data-testid="sidebar-general"><General /></section>
    </main>
  </SidebarProvider>
  <Toaster richColors />
</HashRouter>);
