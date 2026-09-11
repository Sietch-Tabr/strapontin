// Config dédiée, même principe que eslint.config.js. Depuis stylelint 15, les règles purement
// stylistiques ont été retirées du core (stylelint-config-prettier n'a plus lieu d'être et est
// d'ailleurs incompatible avec stylelint 17). Le customSyntax (blocs <style scoped> des .vue) est
// géré automatiquement par stylelint-config-standard-vue, à ne pas dupliquer manuellement.
export default {
  extends: "stylelint-config-standard-vue",
};
