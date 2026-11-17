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
 * Indique une pré-version : un suffixe suit le numéro (« 3.2.0-dev.1 »). Une pré-version d'essai
 * n'est jamais annoncée aux utilisateurs : elle n'a ni entrée de changelog ni entrée de Roadmap.
 * @param {string} version numéro de version sans préfixe
 * @returns {boolean} vrai si la version porte un suffixe de pré-version
 */
export function estPreVersion(version) {
  return /^\d+\.\d+\.\d+-/.test(version);
}

/** Notes d'une pré-version d'essai, qui n'a pas d'entrée de changelog. */
export const NOTES_PRE_VERSION =
  "Pré-version d'essai, non destinée à la production.\n";

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
