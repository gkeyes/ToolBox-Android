import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const directory = path.dirname(fileURLToPath(import.meta.url));
const app = path.resolve(directory, "../..");
const fixture = path.join(directory, "fixtures");

export default defineConfig({
  root: fixture,
  plugins: [
    {
      name: "reading-test-boundaries",
      enforce: "pre",
      resolveId(source, importer) {
        // Mount real reader/close components. Only account, transport and
        // settings-body dependencies are substituted for the controls fixture.
        const file = importer?.split("?")[0];
        const controls = [
          "src/components/ArticleView/components/ActionButtons.jsx",
          "src/components/ArticleView/components/AISummary.jsx",
          "src/components/Settings/Settings.jsx",
        ].map((name) => path.join(app, name));
        if (controls.includes(file)) {
          if (/^@\/components\/Settings\/(General|Appearance|Readability|AI|About|Shortcuts)\.jsx$/.test(source)) {
            return path.join(fixture, "controls-settings-panel.jsx");
          }
          if ([
            "@/toolbox/actions.js", "@/handlers/articleHandlers.js",
            "@/stores/articlesStore", "@/stores/articlesStore.js",
            "@/stores/basicInfoStore.js", "@/stores/settingsStore.js",
            "@/stores/themeStore.js", "@/stores/modalStore.js",
            "@/api/miniflux", "@/api/openai.js",
          ].includes(source)) return path.join(fixture, "controls-state.js");
        }
        // Keep real media leases, source validation, cancellation and cleanup.
        // Only the native transport and server/account boundary are substituted.
        if (importer?.split("?")[0] === path.join(app, "src/toolbox/media.js")) {
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
      { find: "@", replacement: path.join(app, "src") },
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
