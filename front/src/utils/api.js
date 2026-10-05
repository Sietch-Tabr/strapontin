// Point d'accès unique du front à l'API : tout appel passe par appelApi, toute lecture de corps
// par lireJson (règles ESLint no-restricted-globals / no-restricted-syntax).

// En-tête exigé par le serveur sur toute requête modifiante : un en-tête personnalisé impose un
// preflight CORS qu'aucune autre origine, même un sous-domaine voisin, ne peut satisfaire.
export const ENTETE_APPLICATION = "X-Requested-With";
export const VALEUR_ENTETE_APPLICATION = "strapontin";

/// Appel à l'API avec l'en-tête applicatif. Ne rejette que si le serveur est injoignable
/// (réseau coupé, DNS) : une réponse d'erreur du serveur, JSON ou non, est résolue normalement
/// et se lit par `ok`/`status`.
export function appelApi(url, options = {}) {
  const entetes = new Headers(options.headers);
  entetes.set(ENTETE_APPLICATION, VALEUR_ENTETE_APPLICATION);
  return fetch(url, { ...options, headers: entetes });
}

export const MESSAGE_SERVEUR_INDISPONIBLE =
  "Le serveur est momentanément indisponible. Réessayez dans un instant.";

/// Corps JSON d'une réponse, objet vide si le corps est absent ou non JSON (réponse 204, page
/// d'erreur d'un proxy pendant un redémarrage) : jamais d'exception, qui serait sinon affichée
/// comme une erreur réseau. Une erreur serveur (5xx) sans message reçoit `erreur`, que les
/// appelants affichent avant leur message par défaut.
export async function lireJson(reponse) {
  const corps = await reponse.json().catch(() => ({}));
  if (
    reponse.status >= 500 &&
    corps !== null &&
    typeof corps === "object" &&
    !Array.isArray(corps) &&
    !corps.erreur
  ) {
    return { ...corps, erreur: MESSAGE_SERVEUR_INDISPONIBLE };
  }
  return corps;
}
