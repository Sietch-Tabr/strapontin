/**
 * Dit si une navigation du routeur est un vrai changement de page. La résolution de la route
 * initiale part d'un état vide (aucune route correspondante) : elle n'en est pas un, la page a
 * déjà chargé ses données au montage. Un changement de requête ou d'ancre sur la même page n'en
 * est pas un non plus.
 *
 * @param {{ path: string }} vers route atteinte
 * @param {{ path: string, matched: unknown[] }} depuis route quittée
 * @returns {boolean} vrai si les données liées à la page doivent être relues
 */
export function estNavigationReelle(vers, depuis) {
  return depuis.matched.length > 0 && vers.path !== depuis.path;
}
