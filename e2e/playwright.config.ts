import { defineConfig, devices } from "@playwright/test";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";

export default defineConfig({
  testDir: "./tests/e2e",
  testMatch: "**/*.e2e.ts",
  timeout: 60000,
  retries: 0,
  workers: 1,
  fullyParallel: false,

  globalSetup: "./tests/e2e/global-setup.ts",

  reporter: [["list"], ["json", { outputFile: "test-results/results.json" }]],

  use: {
    baseURL: BASE_URL,
    storageState: "tests/e2e/.auth-state.json",
    trace: "on-first-retry",
  },

  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    { name: "firefox", use: { ...devices["Desktop Firefox"] } },
    { name: "webkit", use: { ...devices["Desktop Safari"] } },
    { name: "mobile-chrome", use: { ...devices["Pixel 5"] } },
    { name: "mobile-safari", use: { ...devices["iPhone 12"] } },
  ],
});
