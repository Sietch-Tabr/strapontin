/// Consigne commune aux recherches de Mes artistes et Mes salles : la recherche ne part qu'après
/// un clic sur « Rechercher » (ou la touche Entrée), et pas sous 2 caractères.

export const LONGUEUR_MIN_RECHERCHE = 2;

export const CONSIGNE_RECHERCHE =
  "Saisissez au moins 2 caractères, puis cliquez sur « Rechercher » (ou appuyez sur Entrée).";

/// @param saisie texte du champ de recherche
/// @return `peutLancer` (le bouton est actif) et `explication` (pourquoi il ne l'est pas, vide sinon).
///         Une saisie vide reste permise : elle rétablit la liste complète.
export function etatRecherche(saisie) {
  const longueur = (saisie ?? "").trim().length;
  if (longueur > 0 && longueur < LONGUEUR_MIN_RECHERCHE) {
    return {
      peutLancer: false,
      explication: `Encore ${LONGUEUR_MIN_RECHERCHE - longueur} caractère pour lancer la recherche.`,
    };
  }
  return { peutLancer: true, explication: "" };
}
