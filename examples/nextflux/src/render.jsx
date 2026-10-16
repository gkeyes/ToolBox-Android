import "./i18n";
import { currentThemeMode, initTheme } from "@/stores/themeStore";
import { useStore } from "@nanostores/react";
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
import { installSystemBack } from "@/toolbox/systemBack.js";
import { installNotificationOpen, restoreFeedNotifications } from "@/toolbox/feed-notifications.js";
import { installGlassPress, installLens } from "@/design/lens.js";
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
// System back closes the top panel or the reader before the host exits.
installSystemBack();
// A tapped feed update notification opens its entry or feed.
installNotificationOpen((route) => router.navigate(route));
void restoreFeedNotifications().catch(() => {});
// Edge refraction on the floating glass chrome and buttons, and the glass
// buttons' press state (see design/lens.js, design/glass-buttons.css).
installLens();
installGlassPress();
// Reduced-motion preference must be on <html> before the first frame so CSS tokens snap.
if (settingsState.get().reduceMotion) document.documentElement.dataset.reduceMotion = "true";

// Sonner follows NextFlux's own light/dark choice; left on its default it kept
// light-theme description text on dark glass.
function AppToaster(props) {
  const mode = useStore(currentThemeMode);
  return <Toaster theme={mode} {...props} />;
}

createRoot(document.getElementById("root")).render(
  <>
    <SplashScreen />
    <AppToaster
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
