// Config dédiée, même principe que eslint.config.js. Depuis stylelint 15, les règles purement
// stylistiques ont été retirées du core (stylelint-config-prettier n'a plus lieu d'être et est
// d'ailleurs incompatible avec stylelint 17). Le customSyntax (blocs <style scoped> des .vue) est
// géré automatiquement par stylelint-config-standard-vue, à ne pas dupliquer manuellement.
export default {
  extends: "stylelint-config-standard-vue",
  rules: {
    // Convention BEM française du projet (.claude/rules/frontend-vue.md,
    // antérieure à ce jalon) : bloc-kebab, __element, --modificateur.
    // La regex kebab-case par défaut de stylelint-config-standard rejette
    // le double tiret/underscore du BEM.
    "selector-class-pattern": [
      "^[a-z][a-z0-9]*(-[a-z0-9]+)*(__[a-z][a-z0-9]*(-[a-z0-9]+)*)?(--[a-z][a-z0-9]*(-[a-z0-9]+)*)?$",
      { resolveNestedSelectors: true },
    ],
  },
};
