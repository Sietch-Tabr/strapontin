// Déduit un slug Digitick candidat à partir d'un nom de salle : minuscules, sans accents,
// sans caractère hors [a-z0-9] — reste une simple suggestion, l'admin peut toujours l'ajuster
// manuellement avant de tester/enregistrer.
export function slugDeduit(nom) {
  return nom
    .toLowerCase()
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]/g, "");
}

// Extrait le slug depuis une URL Digitick collée par l'admin (paramètre `site`, ou motif
// d'URL /index-cssNN-{slug}-pgNN.html) — accepte aussi un slug déjà saisi tel quel.
export function extraireSlugDepuisUrl(valeur) {
  try {
    const url = new URL(valeur);
    const site = url.searchParams.get("site");
    if (site) return site.trim();
    const m = url.pathname.match(/\/index-css\d+-(.+?)-pg\d+\.html$/);
    if (m) return m[1].trim();
  } catch (_) {
    // pas une URL valide — traité comme un slug déjà saisi
  }
  return valeur.trim();
}
