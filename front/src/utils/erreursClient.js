import { lireModeAffichage } from "./modeAffichage.js";

const LONGUEUR_MAX_PILE = 2000;

// Sans pile d'appel, l'origine d'une erreur non rattrapée ne peut pas être retrouvée.
function extrairePile(erreur) {
  return erreur instanceof Error && erreur.stack
    ? erreur.stack.slice(0, LONGUEUR_MAX_PILE)
    : undefined;
}

/// Où et dans quelles conditions l'erreur s'est produite : la page (l'`url` d'une erreur JS est
/// celle du script fautif, vide pour une erreur masquée), le mode d'affichage, la connexion et la
/// visibilité de l'onglet.
/// @param env fenêtre à interroger (injectable pour les tests)
export function lireContexteClient(env = globalThis) {
  const lieu = env.location;
  return {
    page: lieu ? `${lieu.pathname}${lieu.search}${lieu.hash}` : undefined,
    modeAffichage: lireModeAffichage(env),
    enLigne: env.navigator?.onLine,
    visibilite: env.document?.visibilityState,
  };
}

/// Le navigateur masque l'erreur d'un script d'une autre origine, ou d'une extension : message
/// « Script error. », sans adresse, ligne, colonne ni pile. Elle ne peut venir du code de
/// l'application (même origine, détails fournis) et ne peut pas être localisée.
function estErreurMasquee(message, url, ligne, colonne, erreur) {
  return (
    String(message ?? "").startsWith("Script error") &&
    !url &&
    !ligne &&
    !colonne &&
    !erreur
  );
}

/// Construit le corps JSON envoyé à POST /api/client/erreur pour une erreur JS non catchée
/// (window.onerror) ; `erreur` est l'objet Error de l'évènement, quand il existe, `contexte` celui
/// de `lireContexteClient`.
export function construireCorpsErreurJs(
  message,
  url,
  ligne,
  colonne,
  erreur,
  contexte = {},
) {
  return {
    type: "js-error",
    message: String(message ?? ""),
    url: url ?? "",
    ligne,
    colonne,
    pile: extrairePile(erreur),
    masquee:
      estErreurMasquee(message, url, ligne, colonne, erreur) || undefined,
    ...contexte,
  };
}

/// Construit le corps JSON envoyé à POST /api/client/erreur pour une promesse rejetée sans
/// handler (window.onunhandledrejection).
export function construireCorpsErreurPromise(raison, urlPage, contexte = {}) {
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
    ...contexte,
  };
}
