import { createRoot } from "react-dom/client";
import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import zhCN from "../../../src/i18n/locales/zh-CN.js";
import SidebarFeedNotifications from "../../../src/components/FeedList/components/SidebarFeedNotifications.jsx?sidebarNotificationsFixture";
import { continuousSync, watchedFeeds } from "./sidebar-notifications-state.js";
import "../../../src/index.css";

await i18n.use(initReactI18next).init({
  lng: "zh-CN", fallbackLng: "zh-CN", resources: { "zh-CN": { translation: zhCN } },
  interpolation: { escapeValue: false },
});

window.sidebarNotificationsFixture = {
  setSync: (enabled) => continuousSync.set(enabled),
  setWatched: (ids) => watchedFeeds.set(ids),
};

createRoot(document.getElementById("root")).render(
  <main style={{ width: "min(320px, 100%)", padding: 8 }}>
    <div className="sidebar-sync-card"><SidebarFeedNotifications /></div>
  </main>,
);
