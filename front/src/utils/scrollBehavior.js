/**
 * Position de scroll à appliquer après une navigation Vue Router.
 *
 * Remet le scroll en haut de page sur une navigation classique (clic sur un lien) ; restaure
 * la position mémorisée par le navigateur sur un retour/avant (bouton précédent/suivant).
 *
 * @param {import('vue-router').RouteLocationNormalized} to route de destination (non utilisé,
 *   conservé pour respecter la signature attendue par `scrollBehavior` de Vue Router)
 * @param {import('vue-router').RouteLocationNormalized} from route de départ (non utilisé)
 * @param {{ left: number, top: number } | null} savedPosition position mémorisée par le
 *   navigateur (non nulle uniquement sur une navigation précédent/suivant)
 * @returns {{ left: number, top: number }} position de scroll à appliquer
 */
export function scrollBehavior(to, from, savedPosition) {
  return savedPosition ?? { top: 0, left: 0 };
}
