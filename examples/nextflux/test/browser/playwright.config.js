import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  testMatch: ["reading.spec.js", "controls.spec.js", "sidebar-notifications.spec.js", "title-filter.spec.js", "article-navigation.spec.js", "media-compat.spec.js", "startup.spec.js", "packaged-startup.spec.js", "touch-press.spec.js", "gallery-swipe.spec.js"],
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  // On CI, failures also become annotations on the check run: the job log and
  // the results artifact are not always reachable for whoever reads the PR.
  reporter: [["list"], ...(process.env.CI ? [["github"]] : []), ["json", { outputFile: "test-results/reading-results.json" }]],
  outputDir: "test-results/artifacts",
  use: {
    baseURL: "http://127.0.0.1:4175",
    browserName: "chromium",
    viewport: { width: 400, height: 900 },
    hasTouch: true,
    deviceScaleFactor: 1,
    serviceWorkers: "block",
    screenshot: "off",
    video: "off",
    trace: "off",
  },
  webServer: [
    {
      command: "npm run serve",
      url: "http://127.0.0.1:4175",
      reuseExistingServer: false,
      timeout: 60_000,
    },
    {
      // Serve the production entry separately: the fixture server substitutes stores.
      command: "../../node_modules/.bin/vite ../.. --config ../../vite.config.js --host 127.0.0.1 --port 4176 --strictPort",
      url: "http://127.0.0.1:4176",
      reuseExistingServer: false,
      timeout: 60_000,
    },
    {
      command: "npm --prefix ../.. run build && ../../node_modules/.bin/vite preview ../.. --config ../../vite.config.js --host 127.0.0.1 --port 4177 --strictPort",
      url: "http://127.0.0.1:4177",
      reuseExistingServer: false,
      timeout: 180_000,
    },
  ],
});
