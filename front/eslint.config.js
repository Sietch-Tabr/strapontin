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
    // Un seul comportement d'appel et de lecture des réponses API (utils/api.js) : en-tête
    // exigé par la protection CSRF du serveur, corps non JSON jamais pris pour une erreur réseau.
    files: ["src/**/*.{js,vue}"],
    ignores: ["src/utils/api.js", "src/**/*.test.js"],
    rules: {
      "no-restricted-globals": [
        "error",
        { name: "fetch", message: "Utiliser appelApi (src/utils/api.js)." },
      ],
      "no-restricted-syntax": [
        "error",
        {
          selector: "CallExpression[callee.property.name='json'][arguments.length=0]",
          message: "Utiliser lireJson (src/utils/api.js).",
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
