import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { VitePWA } from "vite-plugin-pwa";
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

// `inlineDynamicImports option is deprecated` : émis par workbox-build lors du bundling interne
// du service worker (appel Rollup/Rolldown direct, hors pipeline Vite) — aucune option exposée
// par vite-plugin-pwa ne permet de le corriger, non actionnable à ce jour.
// `npm warn deprecated glob`/`source-map` : dépendances transitives de workbox-build, 0
// vulnérabilité (`npm audit`).
export default defineConfig({
  plugins: [
    vue({ template: { transformAssetUrls: { use: [] } } }),
    nettoyerAssets(),
    VitePWA({
      strategies: "injectManifest",
      srcDir: "src",
      filename: "sw.js",
      manifest: false,
      // Registration gérée manuellement dans main.js — ne pas générer registerSW.js.
      injectRegister: false,
      injectManifest: {
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
