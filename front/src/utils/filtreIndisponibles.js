// Filtre « Indisponibles » partagé par l'accueil et Mes spectacles : sans choix explicite dans
// l'URL, le serveur applique la préférence de l'utilisateur, et seul son retour dit ce qui est
// réellement affiché.

/// Valeur explicite de l'URL (« 1 » inclure, « 0 » exclure), sinon null.
export function choixExplicite(valeurUrl) {
  return valeurUrl === "1" || valeurUrl === "0" ? valeurUrl : null;
}

/// Concerts indisponibles affichés : retour du serveur, à défaut le choix de l'URL.
export function indisponiblesAffiches(valeurUrl, reponse) {
  if (typeof reponse?.inclureIndisponibles === "boolean") {
    return reponse.inclureIndisponibles;
  }
  return valeurUrl === "1";
}

/// Valeur d'URL qui inverse l'affichage courant.
export function valeurApresBascule(affiches) {
  return affiches ? "0" : "1";
}

/// Message à afficher quand l'inclusion n'ajoute aucun concert, sinon null.
export function messageSansAjout(affichesAvant, reponse) {
  return !affichesAvant && reponse?.nbIndisponibles === 0
    ? "Aucun concert indisponible à ajouter : tous vos concerts sont déjà affichés."
    : null;
}
