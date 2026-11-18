import { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { Button } from "@heroui/react";
import { HashRouter, useLocation, useNavigate } from "react-router";
import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import ActionButtons from "@/components/ArticleView/components/ActionButtons.jsx";
import Settings from "@/components/Settings/Settings.jsx";
import CustomModal from "@/components/ui/CustomModal.jsx";
import CustomAlertDialog from "@/components/ui/CustomAlertDialog.jsx";
import { aiSummaries, clearSummary } from "@/stores/aiStore.js";
import { settingsState as appSettings } from "@/stores/settingsStore.js";
import { speechState } from "@/stores/speechStore.js";
import { installOverlayJanitor } from "@/toolbox/overlayJanitor.js";
import { installSystemBack } from "@/toolbox/systemBack.js";
import { activeArticle, calls, currentThemeMode, hasIntegrations, loadingOriginContent, settingsModalOpen, settingsState, summaryBoundary } from "./controls-state.js";
import "../../../src/index.css";
import "../../../src/compact-controls.css";

await i18n.use(initReactI18next).init({
  lng: "en", fallbackLng: "en", interpolation: { escapeValue: false },
  resources: { en: { translation: {
    common: { close: "Close", previous: "Previous", next: "Next", read: "Read", unread: "Unread", star: "Star", unstar: "Unstar", share: "Share", settings: "Settings" },
    articleView: { aiSummary: "AI summary", aiSummarize: "AI summary", showSummary: "Show summary", getFullText: "Full text", saveToThirdParty: "Save externally" },
    settings: { general: { title: "General" } },
  } } },
});
const theme = new URLSearchParams(location.search).get("theme") === "dark" ? "dark" : "light";
document.documentElement.dataset.theme = theme;
document.documentElement.classList.toggle("dark", theme === "dark");
currentThemeMode.set(theme);
aiSummaries.set({ 42: { loading: false, summary: "Fixture summary.", error: null } });

function ControlsFixture() {
  const route = useLocation();
  const navigate = useNavigate();
  const [modal, setModal] = useState(false);
  const [alert, setAlert] = useState(false);
  const [footerSheet, setFooterSheet] = useState(false);
  const [confirmations, setConfirmations] = useState(0);
  const showingArticle = route.pathname.includes("/article/");

  useEffect(() => {
    window.controlsFixture = {
      // Real app settings/speech stores used by the production speech controller.
      speech: {
        configure: () => appSettings.set({ ...appSettings.get(), speechApiKey: "fixture-speech-key", speechRegion: "global", speechPrefetch: false }),
        phase: () => speechState.get().phase,
      },
      summary: {
        ...summaryBoundary,
        clear: (id = 42) => clearSummary(id),
        state: (id = 42) => aiSummaries.get()[id] ?? null,
        set: (id, state) => aiSummaries.set({ ...aiSummaries.get(), [id]: state }),
      },
      setArticle: (id) => {
        activeArticle.set({ ...activeArticle.get(), id, title: `Fixture article ${id}`, url: `https://example.invalid/article/${id}` });
        navigate(`/feed/1/article/${id}`);
      },
      calls,
      setIntegrations: (value) => hasIntegrations.set(value),
      setBusy: (value) => loadingOriginContent.set(value),
      setAI: (value) => settingsState.set({ ...settingsState.get(), aiApiKey: value ? "fixture" : "" }),
      pending: null,
    };
    return () => { delete window.controlsFixture; };
  }, [navigate]);

  return <>
    {showingArticle ? <>
      <ActionButtons />
      <main data-testid="reader-body" style={{ padding: 20 }}>
        <h1>NextFlux reading controls</h1>
        <p>Article text keeps its original size. Close, read, star, AI, full text and share use aligned controls.</p>
      </main>
    </> : <p data-testid="article-list">Article list</p>}
    <output data-testid="route">{route.pathname}</output>
    <div style={{ display: "flex", flexWrap: "wrap", gap: 12, padding: 20 }}>
      <button onClick={() => setModal(true)}>Open modal</button>
      <button onClick={() => setFooterSheet(true)}>Open sheet with footer</button>
      <button onClick={() => settingsModalOpen.set(true)}>Open settings</button>
      <button onClick={() => setAlert(true)}>Open alert</button>
    </div>
    <output data-testid="confirmations">{confirmations}</output>
    <CustomModal open={modal} onOpenChange={setModal} title="Modal heading with enough text to wrap on a narrow display">
      <p style={{ padding: 16 }}>Modal body fixture</p>
    </CustomModal>
    {/* The rename / add-category / edit-feed shape. Labels of unequal length, so alert-only
        sizing (content-width buttons) leaking onto sheets would show as unequal widths. */}
    <CustomModal open={footerSheet} onOpenChange={setFooterSheet} title="Rename"
      footer={<><Button variant="tertiary" onPress={() => setFooterSheet(false)}>取消</Button><Button onPress={() => setFooterSheet(false)}>保存更改</Button></>}>
      <p style={{ padding: 16 }}>Sheet body fixture</p>
    </CustomModal>
    <Settings />
    <CustomAlertDialog title="Confirm fixture action" content="Pending operations must still prevent dismissal."
      isOpen={alert} onClose={() => setAlert(false)} cancelText="Cancel" confirmText="Confirm"
      onConfirm={() => new Promise((resolve) => {
        setConfirmations((value) => value + 1);
        window.controlsFixture.pending = resolve;
      })} />
  </>;
}

// Same janitor as the app (render.jsx); the fixture's only store-driven overlay is Settings.
window.controlsOverlayJanitor = installOverlayJanitor({ isOverlayExpected: () => settingsModalOpen.get(), settleMs: 300 });
window.controlsStopSystemBack = installSystemBack();

createRoot(document.getElementById("root")).render(<HashRouter><ControlsFixture /></HashRouter>);
