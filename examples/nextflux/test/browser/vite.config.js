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
const controlBoundaries = new Set([
  "toolbox/actions.js", "handlers/articleHandlers.js",
  "stores/articlesStore", "stores/articlesStore.js",
  "stores/basicInfoStore.js", "stores/settingsStore.js",
  "stores/themeStore.js", "stores/modalStore.js",
  "stores/authStore.js", "stores/feedsStore.js", "toolbox/network.js",
  "api/miniflux", "api/miniflux.js", "api/openai.js",
].map((name) => path.join(sourceRoot, name)));

export default defineConfig({
  root: fixture,
  plugins: [
    {
      name: "reading-test-boundaries",
      enforce: "pre",
      resolveId(source, importer) {
        // Vite's alias plugin may resolve @ before this hook. Recognize both
        // forms, and scope mocks to the real controls rather than all imports.
        const file = importer?.split("?")[0];
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
