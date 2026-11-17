// Mise en forme des notes d'une release GitHub à partir du changelog utilisateur : mêmes catégories
// et mêmes phrases que la page /changelog, sans numéro de ticket ni détail technique.

/**
 * Retrouve l'entrée du changelog d'une version.
 * @param {Array<{entete: string, categories: Array}>} versions entrées du changelog, la plus récente d'abord
 * @param {string} version numéro de version sans préfixe, par exemple « 3.2.0 »
 * @returns {object|undefined} l'entrée de la version, ou undefined si elle est absente
 */
export function trouverEntree(versions, version) {
  return versions.find((entree) => entree.entete.startsWith(`v ${version} —`));
}

/**
 * Formate une entrée du changelog en Markdown, une section par catégorie.
 * @param {{categories: Array<{categorie: string, items: string[]}>}} entree entrée du changelog
 * @returns {string} les notes de release, terminées par un saut de ligne
 */
export function formaterNotes(entree) {
  const sections = entree.categories.map(
    ({ categorie, items }) =>
      `## ${categorie}\n\n${items.map((item) => `- ${item}`).join("\n")}\n`,
  );
  return sections.join("\n");
}
