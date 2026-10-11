import "./i18n";
import { initTheme } from "@/stores/themeStore";
import { settingsState } from "@/stores/settingsStore.js";
import { createRoot } from "react-dom/client";
import "./index.css";
import { router } from "@/routes/index.jsx";
import { RouterProvider } from "react-router";
import SplashScreen from "@/components/SplashScreen";
import { Toaster } from "sonner";
import { isModalOpen } from "@/stores/modalStore.js";
import { imageGalleryActive } from "@/stores/articlesStore.js";
import { installOverlayJanitor } from "@/toolbox/overlayJanitor.js";
import {
  Info,
  CircleCheck,
  TriangleAlert,
  CircleX,
  Loader2,
} from "lucide-react";

// 初始化主题
initTheme();
// Release any floating layer left over the page after its close path was
// interrupted (see overlayJanitor.js); never while an overlay is meant to be open.
installOverlayJanitor({ isOverlayExpected: () => isModalOpen.get() || imageGalleryActive.get() });
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
