import { defineConfig } from "vitest/config";

// Config dédiée, volontairement séparée de vite.config.js (#781) : la portée initiale se limite aux
// fonctions pures des composants admin, pas de montage de composant ni de jsdom, donc pas besoin des
// plugins Vue/PWA/nettoyage d'assets utilisés pour le build applicatif.
export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.test.js"],
  },
});
