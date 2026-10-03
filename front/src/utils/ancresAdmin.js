// Anciennes ancres de l'administration (favoris, liens d'emails d'alerte) vers l'onglet qui
// les regroupe désormais.
const ANCRES_HISTORIQUES = {
  "#signalements": { hash: "#a-traiter" },
  "#concerts": { hash: "#a-traiter" },
  "#salles": { hash: "#referentiel", query: { entite: "salle" } },
  "#artistes": { hash: "#referentiel", query: { entite: "artiste" } },
};

/**
 * Redirection d'une ancre historique de `/admin` vers l'onglet qui la regroupe.
 *
 * @param {{ path: string, hash: string, query?: Record<string, unknown> }} route route visée
 * @returns {{ path: string, hash: string, query: Record<string, unknown>, replace: true } | null}
 *   la route de remplacement, ou `null` si aucune redirection ne s'applique
 */
export function redirectionAncreAdmin(route) {
  if (route.path !== "/admin") return null;
  const cible = ANCRES_HISTORIQUES[route.hash];
  if (!cible) return null;
  return {
    path: "/admin",
    hash: cible.hash,
    query: { ...route.query, ...cible.query },
    replace: true,
  };
}
