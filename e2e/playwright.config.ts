import { defineConfig, devices } from "@playwright/test";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";

export default defineConfig({
  testDir: "./tests/e2e",
  testMatch: "**/*.e2e.ts",
  timeout: 60000,
  // Retries toujours actifs (pas seulement en CI — Woodpecker ne lance pas les E2E, ce projet
  // les exécute uniquement en local/manuel, la condition CI ne se déclenchait donc jamais).
  // webkit/mobile-safari (même moteur WebKit) présentent un blocage réseau intermittent
  // page.goto (aucune requête, page blanche) confirmé identique sur du code non modifié et
  // après suppression de navigationPreload — c'est une instabilité du driver Playwright-WebKit
  // sous charge soutenue, pas un bug applicatif. Le retry absorbe cette classe de flakiness,
  // pratique standard pour les suites E2E navigateur (voir wiki Développement-local).
  retries: 2,
  workers: 1,
  fullyParallel: false,

  globalSetup: "./tests/e2e/global-setup.ts",

  reporter: [["list"], ["json", { outputFile: "test-results/results.json" }]],

  use: {
    baseURL: BASE_URL,
    storageState: "tests/e2e/.auth-user.json",
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
