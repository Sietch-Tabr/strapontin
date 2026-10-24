const LONGUEUR_MAX_PILE = 2000;

// Sans pile d'appel, l'origine d'une erreur non rattrapée ne peut pas être retrouvée.
function extrairePile(erreur) {
  return erreur instanceof Error && erreur.stack
    ? erreur.stack.slice(0, LONGUEUR_MAX_PILE)
    : undefined;
}

/// Construit le corps JSON envoyé à POST /api/client/erreur pour une erreur JS non catchée
/// (window.onerror) ; `erreur` est l'objet Error de l'évènement, quand il existe.
export function construireCorpsErreurJs(message, url, ligne, colonne, erreur) {
  return {
    type: "js-error",
    message: String(message ?? ""),
    url: url ?? "",
    ligne,
    colonne,
    pile: extrairePile(erreur),
  };
}

/// Construit le corps JSON envoyé à POST /api/client/erreur pour une promesse rejetée sans
/// handler (window.onunhandledrejection).
export function construireCorpsErreurPromise(raison, urlPage) {
  const message =
    raison instanceof Error
      ? raison.message || raison.toString()
      : String(raison ?? "");
  return {
    type: "promise-rejection",
    message,
    url: urlPage ?? "",
    nom: raison instanceof Error ? raison.name : undefined,
    pile: extrairePile(raison),
  };
}
