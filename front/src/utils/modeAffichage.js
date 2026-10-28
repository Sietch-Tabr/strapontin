export const ENTETE_MODE_AFFICHAGE = "X-Mode-Affichage";

/// Mode d'affichage de l'application : `pwa` si elle est installée, `navigateur` sinon. Le
/// serveur ne peut pas le déduire du User-Agent, identique dans les deux cas.
/// @param env fenêtre à interroger (injectable pour les tests)
export function lireModeAffichage(env = globalThis) {
  try {
    // iOS n'annonce pas `display-mode` aux anciennes versions : `navigator.standalone` le remplace.
    const installee =
      env.matchMedia?.("(display-mode: standalone)")?.matches === true ||
      env.navigator?.standalone === true;
    return installee ? "pwa" : "navigateur";
  } catch {
    return "navigateur";
  }
}
