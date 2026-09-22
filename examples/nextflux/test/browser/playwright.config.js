import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  testMatch: "reading.spec.js",
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [["list"], ["json", { outputFile: "test-results/reading-results.json" }]],
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
  webServer: {
    command: "npm run serve",
    url: "http://127.0.0.1:4175",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
