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

// #771 : deux avertissements de build non actionnables, investigués et documentés plutôt que
// corrigés au jugé.
// - `inlineDynamicImports option is deprecated` : émis par Rollup lors du bundling interne du
//   service worker par workbox-build (dépendance de vite-plugin-pwa), pas par une option
//   explicite de ce fichier — aucune option `injectManifest`/`VitePWA` documentée ne permet de
//   le remplacer par `codeSplitting: false`. vite-plugin-pwa@1.3.0 (dernière version publiée,
//   vérifié via `npm view vite-plugin-pwa versions`) ne l'a pas encore corrigé en interne.
// - `npm warn deprecated glob@11.1.0` / `source-map@0.8.0-beta.0` : dépendances transitives de
//   workbox-build (via vite-plugin-pwa), aucune vulnérabilité (`npm audit` : 0 vulnérabilité).
// À réévaluer à la prochaine mise à jour majeure de vite-plugin-pwa.
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
