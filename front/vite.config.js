import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { VitePWA } from "@unplugin-pwa/vite";
import { rm } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const dossierAssets = fileURLToPath(
  new URL("../java/web/src/main/resources/static/assets", import.meta.url),
);

// Vide uniquement static/assets/ avant chaque build, sans toucher aux autres ressources
// statiques (images/, icons/). Remplace emptyOutDir qui viderait tout.
function nettoyerAssets() {
  return {
    name: "nettoyer-assets",
    apply: "build",
    async buildStart() {
      await rm(dossierAssets, { recursive: true, force: true });
    },
  };
}

export default defineConfig({
  plugins: [
    vue({ template: { transformAssetUrls: { use: [] } } }),
    nettoyerAssets(),
    VitePWA({
      strategies: "buildSW",
      filename: "sw.js",
      manifest: false,
      // Registration gérée manuellement dans main.js — ne pas générer registerSW.js.
      injectRegister: false,
      buildSW: {
        swSrc: "src/sw.js",
        globPatterns: [
          "index.html",
          "offline-fallback.js",
          "assets/**/*.{js,css}",
          "images/**/*.{png,webp}",
          "icons/**/*.svg",
        ],
      },
    }),
  ],
  build: {
    outDir: "../java/web/src/main/resources/static",
    assetsDir: "assets",
    emptyOutDir: false,
  },
});
