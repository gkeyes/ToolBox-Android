import { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { HashRouter, useLocation } from "react-router-dom";
import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import ActionButtons from "@/components/ArticleView/components/ActionButtons.jsx";
import AISummary from "@/components/ArticleView/components/AISummary.jsx";
import Settings from "@/components/Settings/Settings.jsx";
import CustomModal from "@/components/ui/CustomModal.jsx";
import CustomAlertDialog from "@/components/ui/CustomAlertDialog.jsx";
import { aiSummaries } from "@/stores/aiStore.js";
import { calls, currentThemeMode, hasIntegrations, loadingOriginContent, settingsModalOpen, settingsState } from "./controls-state.js";
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
  const [modal, setModal] = useState(false);
  const [alert, setAlert] = useState(false);
  const [confirmations, setConfirmations] = useState(0);
  const showingArticle = route.pathname.includes("/article/");

  useEffect(() => {
    window.controlsFixture = {
      calls,
      setIntegrations: (value) => hasIntegrations.set(value),
      setBusy: (value) => loadingOriginContent.set(value),
      setAI: (value) => settingsState.set({ ...settingsState.get(), aiApiKey: value ? "fixture" : "" }),
      pending: null,
    };
    return () => { delete window.controlsFixture; };
  }, []);

  return <>
    {showingArticle ? <>
      <ActionButtons />
      <main style={{ padding: 20 }}>
        <h1>NextFlux reading controls</h1>
        <p>Article text keeps its original size. Close, read, star, AI, full text and share use aligned controls.</p>
        <AISummary articleId={42} />
      </main>
    </> : <p data-testid="article-list">Article list</p>}
    <output data-testid="route">{route.pathname}</output>
    <div style={{ display: "flex", flexWrap: "wrap", gap: 12, padding: 20 }}>
      <button onClick={() => setModal(true)}>Open modal</button>
      <button onClick={() => settingsModalOpen.set(true)}>Open settings</button>
      <button onClick={() => setAlert(true)}>Open alert</button>
    </div>
    <output data-testid="confirmations">{confirmations}</output>
    <CustomModal open={modal} onOpenChange={setModal} title="Modal heading with enough text to wrap on a narrow display">
      <p style={{ padding: 16 }}>Modal body fixture</p>
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

createRoot(document.getElementById("root")).render(<HashRouter><ControlsFixture /></HashRouter>);
