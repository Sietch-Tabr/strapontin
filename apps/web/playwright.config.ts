import { defineConfig, devices } from "@playwright/test";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";

export default defineConfig({
  testDir: "./tests/e2e",
  testMatch: "**/*.e2e.ts",
  timeout: 30000,
  retries: 0,
  workers: 1,
  fullyParallel: false,

  globalSetup: "./tests/e2e/global-setup.ts",

  reporter: [
    ["list"],
    ["json", { outputFile: "test-results/results.json" }],
  ],

  use: {
    baseURL: BASE_URL,
    storageState: "tests/e2e/.auth-state.json",
    trace: "on-first-retry",
  },

  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
