/// Présentation du test d'un lieu Eventim saisi à la main.

/// @param resultat `{injoignable}` ou `{nbConcerts}` renvoyé par le test, null avant tout test
/// @return phrase affichée sous le champ, vide avant tout test
export function formaterResultatTestLieu(resultat) {
  if (!resultat) return "";
  if (resultat.injoignable) return "Eventim injoignable ✗";
  const nb = resultat.nbConcerts ?? 0;
  return nb > 0 ? `✓ ${nb} concert${nb > 1 ? "s" : ""}` : "✓ aucun concert";
}
