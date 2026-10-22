import base from "/home/claude/toolbox-android/examples/nextflux/test/browser/playwright.config.js";
export default { ...base, testDir: "/home/claude/toolbox-android/examples/nextflux/test/browser", use: { ...base.use, launchOptions: { executablePath: "/opt/pw-browsers/chromium" } } };
