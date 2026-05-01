import { defineConfig } from "vitest/config";
import { resolve } from "path";

export default defineConfig({
  test: {
    name: "fonctionnel",
    include: ["src/tests/fonctionnel/**/*.test.ts"],
    environment: "node",
    env: {
      BETTER_AUTH_SECRET: "secret-test-vitest-32-caracteres-minimum",
      NODE_ENV: "test",
    },
  },
  resolve: {
    alias: {
      $lib: resolve(__dirname, "src/lib"),
    },
  },
});
