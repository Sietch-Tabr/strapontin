/// Construit le corps JSON envoyé à POST /api/client/erreur pour une erreur JS non catchée
/// (window.onerror).
export function construireCorpsErreurJs(message, url, ligne, colonne) {
  return {
    type: "js-error",
    message: String(message ?? ""),
    url: url ?? "",
    ligne,
    colonne,
  };
}

/// Construit le corps JSON envoyé à POST /api/client/erreur pour une promesse rejetée sans
/// handler (window.onunhandledrejection).
export function construireCorpsErreurPromise(raison, urlPage) {
  const message =
    raison instanceof Error
      ? raison.message || raison.toString()
      : String(raison ?? "");
  return { type: "promise-rejection", message, url: urlPage ?? "" };
}
