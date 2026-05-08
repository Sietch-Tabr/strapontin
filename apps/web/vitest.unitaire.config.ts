import { defineConfig } from "vitest/config";
import { resolve } from "path";

export default defineConfig({
  test: {
    name: "unitaire",
    include: ["src/tests/unitaire/**/*.test.ts"],
    environment: "node",
  },
  resolve: {
    alias: {
      $lib: resolve(__dirname, "src/lib"),
    },
  },
});
