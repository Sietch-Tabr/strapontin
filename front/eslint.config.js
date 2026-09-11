// Config ESLint volontairement séparée de vite.config.js/vitest.config.js (même principe que
// #781) : lint des bugs potentiels et bonnes pratiques Vue, pas du formatage — déjà géré par
// Prettier via le hook d'écriture (eslint-config-prettier désactive les règles redondantes).
import js from "@eslint/js";
import pluginVue from "eslint-plugin-vue";
import eslintConfigPrettier from "eslint-config-prettier";
import globals from "globals";

export default [
  {
    ignores: ["dist/**", "src/sw.js"],
  },
  js.configs.recommended,
  ...pluginVue.configs["flat/recommended"],
  {
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: {
        ...globals.browser,
      },
    },
    rules: {
      // Convention JS répandue : préfixe _ pour un paramètre/variable volontairement ignoré.
      "no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
        },
      ],
    },
  },
  {
    files: ["vite.config.js", "vitest.config.js", "scripts/**/*.mjs"],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  eslintConfigPrettier,
];
