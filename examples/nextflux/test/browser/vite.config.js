import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const directory = path.dirname(fileURLToPath(import.meta.url));
const app = path.resolve(directory, "../..");
const fixture = path.join(directory, "fixtures");
const sourceRoot = path.join(app, "src");
const controlComponents = [
  "components/ArticleView/components/ActionButtons.jsx",
  "components/ArticleView/components/AISummary.jsx",
  "components/ArticleView/components/FullTextAdaptButton.jsx",
  "components/ArticleView/components/ArticleTitleFilterButton.jsx",
  "components/ArticleList/components/FeedTitleFilterButton.jsx",
  "components/Settings/Settings.jsx",
].map((name) => path.join(sourceRoot, name));
const sidebarComponents = new Set([
  "components/FeedList/FeedListSidebar.jsx",
  "components/FeedList/components/SidebarBackgroundSync.jsx",
  "components/FeedList/components/SidebarHiddenFeeds.jsx",
  "components/Settings/General.jsx",
].map((name) => path.join(sourceRoot, name)));
const sidebarBoundaries = new Set([
  "stores/feedsStore.js", "stores/syncStore.js", "stores/modalStore", "stores/modalStore.js",
  "db/storage.js", "toolbox/background.js",
].map((name) => path.join(sourceRoot, name)));
const sidebarCatalogViews = new Set([
  "ArticlesGroup.jsx", "FeedsGroup.jsx", "SyncButton.jsx", "AddFeedButton.jsx", "ProfileButton.jsx",
].map((name) => path.join(sourceRoot, "components/FeedList/components", name)));
const controlBoundaries = new Set([
  "toolbox/actions.js", "handlers/articleHandlers.js",
  "stores/articlesStore", "stores/articlesStore.js",
  "stores/basicInfoStore.js", "stores/settingsStore.js",
  "stores/themeStore.js", "stores/modalStore.js",
  "stores/authStore.js", "stores/feedsStore.js", "toolbox/network.js",
  "api/miniflux", "api/miniflux.js", "api/openai.js",
].map((name) => path.join(sourceRoot, name)));
const navigationComponents = new Set([
  "components/ArticleView/ArticleView.jsx",
  "components/ArticleView/components/ContinuousNextUnread.jsx",
  "components/ArticleView/components/ArticlePageContent.jsx",
  "components/ArticleView/components/ArticleHeader.jsx",
  "components/ArticleList/components/EmptyPlaceholder.jsx",
  "components/ui/FeedIcon.jsx", "hooks/useArticleSwipeBack.js",
].map((name) => path.join(sourceRoot, name)));
const navigationBoundaries = new Set([
  "stores/articlesStore", "stores/articlesStore.js", "stores/modalStore.js",
  "stores/settingsStore", "stores/settingsStore.js", "stores/themeStore.js",
  "stores/syncStore.js", "db/storage", "db/storage.js", "handlers/articleHandlers.js",
].map((name) => path.join(sourceRoot, name)));

export default defineConfig({
  root: fixture,
  // The navigation fixture uses a different account boundary at its importers.
  // Scan the original entries together; navigation imports resolve individually.
  optimizeDeps: { entries: ["index.html", "controls.html", "controls-main.jsx", "controls-sidebar.jsx", "title-filter.html", "media-compat.html"] },
  plugins: [
    {
      name: "reading-test-boundaries",
      enforce: "pre",
      resolveId(source, importer) {
        // Vite's alias plugin may resolve @ before this hook. Recognize both
        // forms, and scope mocks to the real controls rather than all imports.
        const file = importer?.split("?")[0];
        if (sidebarComponents.has(file)) {
          const resolved = source.startsWith("@/") ? path.join(sourceRoot, source.slice(2)) : source;
          if (sidebarBoundaries.has(resolved)) return path.join(fixture, "controls-sidebar-state.js");
          if (file === path.join(sourceRoot, "components/FeedList/FeedListSidebar.jsx") && sidebarCatalogViews.has(resolved)) {
            return path.join(fixture, "controls-sidebar-placeholder.jsx");
          }
        }
        if (navigationComponents.has(file)) {
          const resolved = source.startsWith("@/") ? path.join(sourceRoot, source.slice(2)) : source;
          if (navigationBoundaries.has(resolved) || resolved === path.join(fixture, "stores.js")) {
            return path.join(fixture, "article-navigation-state.js");
          }
          if (resolved === path.join(sourceRoot, "components/ArticleView/components/ActionButtons.jsx")) {
            return path.join(fixture, "article-navigation-toolbar.jsx");
          }
        }
        if (controlComponents.includes(file)) {
          const resolved = source.startsWith("@/") ? path.join(sourceRoot, source.slice(2)) : source;
          if (path.dirname(resolved) === path.join(sourceRoot, "components/Settings") &&
              /^(General|Appearance|Readability|AI|About|Shortcuts)\.jsx$/.test(path.basename(resolved))) {
            return path.join(fixture, "controls-settings-panel.jsx");
          }
          if (controlBoundaries.has(resolved) || resolved === path.join(fixture, "stores.js")) {
            return path.join(fixture, "controls-state.js");
          }
        }
        // Keep real media leases, source validation, cancellation and cleanup.
        // Only the native transport and server/account boundary are substituted.
        if (file === path.join(sourceRoot, "toolbox/media.js")) {
          if (source === "./mediaTransport.js") return path.join(fixture, "media-transport.js");
          if (source === "./network.js") return path.join(fixture, "network.js");
        }
      },
    },
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: [
      { find: "@/stores/articlesStore.js", replacement: path.join(fixture, "stores.js") },
      { find: "@/stores/modalStore.js", replacement: path.join(fixture, "stores.js") },
      { find: "@", replacement: sourceRoot },
    ],
  },
  // Tailwind's Vite plugin processes the production CSS; no second PostCSS pass.
  css: { postcss: { plugins: [] } },
  server: {
    host: "127.0.0.1",
    port: 4175,
    strictPort: true,
    hmr: false,
    fs: { allow: [app] },
  },
});
