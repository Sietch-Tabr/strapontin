/// Présentation des suggestions de la recherche d'artistes : chaque résultat dit d'où il vient et
/// de quoi le distinguer d'un voisin de même nom.

const LIBELLES_SOURCE = {
  wikidata: "Wikidata",
  deezer: "Deezer",
  base: "Déjà connu",
};

/// @param source origine de la suggestion (`base`, `deezer`, `wikidata`)
/// @return libellé du badge
export function libelleSourceSuggestion(source) {
  return LIBELLES_SOURCE[source] ?? source;
}

/// @param suggestion `{nom, source, description, fans}` renvoyée par l'API
/// @return phrase qui distingue le résultat : description Wikidata, popularité Deezer, ou origine
export function detailSuggestion(suggestion) {
  if (suggestion.description) return suggestion.description;
  if (suggestion.source === "deezer" && suggestion.fans != null) {
    const fans = new Intl.NumberFormat("fr-FR").format(suggestion.fans);
    return suggestion.fans > 1
      ? `${fans} fans sur Deezer`
      : `${fans} fan sur Deezer`;
  }
  if (suggestion.source === "base") {
    return "Artiste présent dans les concerts de Strapontin";
  }
  return "";
}
