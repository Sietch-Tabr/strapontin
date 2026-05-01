import { defineConfig } from "vitest/config";

const MDT_PASSWORD = "9e3b517c93bc15ea12719401a2857085";

export default defineConfig({
  test: {
    name: "integration",
    include: ["src/__tests__/integration/**/*.integration.test.ts"],
    environment: "node",
    globalSetup: ["src/__tests__/integration/global-setup.ts"],
    env: {
      DATABASE_URL: `postgresql://strapontin:${MDT_PASSWORD}@127.0.0.1:15432/strapontin_test`,
      REDIS_URL: "redis://127.0.0.1:16379",
      BETTER_AUTH_SECRET: "secret-test-vitest-32-caracteres-minimum",
    },
    testTimeout: 30000,
    hookTimeout: 15000,
    sequence: { concurrent: false },
    maxWorkers: 1,
  },
});
