import { initializeBackground } from "@/toolbox/background.js";
import { Outlet } from "react-router";
import "./App.css";
import { useEffect, useState } from "react";
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar.jsx";
import FeedListSidebar from "@/components/FeedList/FeedListSidebar.jsx";
import { authState } from "@/stores/authStore.js";
import { startAutoSync, stopAutoSync } from "@/stores/syncStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import { useStore } from "@nanostores/react";
import AppModals from "@/components/AppModals.jsx";
import { useHotkeys } from "@/hooks/useHotkeys.js";
import { checkIntegrations } from "@/stores/basicInfoStore.js";
import { useZoom } from "@/hooks/useZoom.js";
import { useBorderRadius } from "@/hooks/useBorderRadius.js";
import FontLoader from "@/components/FontLoader.jsx";
import { useReducedMotionAttribute } from "@/design/motion.js";

// Router hooks re-render their caller on every route change; keep them in a
// leaf that renders nothing instead of in App, which owns the whole layout.
function Hotkeys() {
  useHotkeys();
  return null;
}

function App() {
  const { syncInterval, reduceMotion } = useStore(settingsState);
  const [backgroundReady, setBackgroundReady] = useState(false);
  useEffect(() => {
    let mounted = true;
    initializeBackground().then(
      () => { if (mounted) setBackgroundReady(true); },
      () => { if (mounted) setBackgroundReady(true); },
    );
    return () => { mounted = false; };
  }, []);
  useEffect(() => {
    if (!backgroundReady) return;
    // 检查认证状态并启动自动同步
    const auth = authState.get();
    if (auth.username) {
      startAutoSync();
      return stopAutoSync;
    }
  }, [syncInterval, backgroundReady]);
  // 检查第三方集成状态
  useEffect(() => {
    checkIntegrations();
  }, []);

  useZoom();
  useReducedMotionAttribute(reduceMotion);
  useBorderRadius();
  return (
    <SidebarProvider>
      <Hotkeys />
      <FontLoader />
      <FeedListSidebar />
      <SidebarInset className="min-w-0">
        <Outlet />
      </SidebarInset>
      <AppModals />
    </SidebarProvider>
  );
}

export default App;
