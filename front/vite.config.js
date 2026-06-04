import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: "../java/web/src/main/resources/static",
    assetsDir: "assets",
    emptyOutDir: false,
  },
});
