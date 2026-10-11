import "./i18n";
import { initTheme } from "@/stores/themeStore";
import { settingsState } from "@/stores/settingsStore.js";
import { createRoot } from "react-dom/client";
import "./index.css";
import { router } from "@/routes/index.jsx";
import { RouterProvider } from "react-router";
import SplashScreen from "@/components/SplashScreen";
import { Toaster } from "sonner";
import {
  Info,
  CircleCheck,
  TriangleAlert,
  CircleX,
  Loader2,
} from "lucide-react";

// 初始化主题
initTheme();
// Reduced-motion preference must be on <html> before the first frame so CSS tokens snap.
if (settingsState.get().reduceMotion) document.documentElement.dataset.reduceMotion = "true";

createRoot(document.getElementById("root")).render(
  <>
    <SplashScreen />
    <Toaster
      position="bottom-center"
      offset={{ bottom: "calc(16px + env(safe-area-inset-bottom))" }}
      mobileOffset={{ bottom: "calc(16px + env(safe-area-inset-bottom))" }}
      icons={{
        loading: <Loader2 className="size-4! animate-spin! text-accent!" />,
        success: <CircleCheck className="size-4! nextflux-toast-icon-success" />,
        info: <Info className="size-4! nextflux-toast-icon-info" />,
        warning: <TriangleAlert className="size-4! nextflux-toast-icon-warning" />,
        error: <CircleX className="size-4! nextflux-toast-icon-danger" />,
      }}
      toastOptions={{
        classNames: {
          toast: "nextflux-toast",
          title: "nextflux-toast-title",
          description: "nextflux-toast-description",
        },
      }}
    />
    <RouterProvider
      router={router}
      future={{
        v7_startTransition: true,
        v7_relativeSplatPath: true,
        v7_fetcherPersist: true,
        v7_normalizeFormMethod: true,
        v7_partialHydration: true,
        v7_skipActionErrorRevalidation: true,
      }}
    />
  </>,
);
