import {defineConfig,devices} from '@playwright/test';
export default defineConfig({
  testDir:'test',testMatch:'**/*.spec.ts',fullyParallel:false,workers:1,retries:0,
  reporter:[['list'],['json',{outputFile:'test-results/browser-results.json'}]],
  use:{baseURL:'http://127.0.0.1:4173',trace:'retain-on-failure',screenshot:'only-on-failure',reducedMotion:'reduce'},
  projects:[{name:'mobile',use:{...devices['Pixel 7'],viewport:{width:393,height:852}}},{name:'desktop',use:{viewport:{width:1280,height:900}}}],
  webServer:{command:'npm run preview',url:'http://127.0.0.1:4173',reuseExistingServer:!process.env.CI},
});
