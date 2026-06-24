import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { rm } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const dossierAssets = fileURLToPath(
  new URL("../java/web/src/main/resources/static/assets", import.meta.url),
);

// Vide uniquement static/assets/ avant chaque build, sans toucher aux autres ressources
// statiques (css/, fonts/, images/, icons/, sw.js). Remplace emptyOutDir qui viderait tout.
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
  ],
  build: {
    outDir: "../java/web/src/main/resources/static",
    assetsDir: "assets",
    emptyOutDir: false,
  },
});
