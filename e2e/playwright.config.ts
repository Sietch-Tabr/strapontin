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
    // Les tests purement API (non liés au rendu) tournent sur chromium uniquement.
    // Sur les autres projets, on les exclut pour éviter d'épuiser le rate limiter bucket4j.
    {
      name: "firefox",
      use: { ...devices["Desktop Firefox"] },
      grepInvert: /Scénario: API auth/,
    },
    {
      name: "webkit",
      use: { ...devices["Desktop Safari"] },
      grepInvert: /Scénario: API auth/,
    },
    {
      name: "mobile-chrome",
      use: { ...devices["Pixel 5"] },
      grepInvert: /Scénario: API auth/,
    },
    {
      name: "mobile-safari",
      use: { ...devices["iPhone 12"] },
      grepInvert: /Scénario: API auth/,
    },
  ],
});
