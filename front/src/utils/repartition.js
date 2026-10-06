// Présélection des lieux d'une salle à déplacer avec les libellés choisis : même
// rapprochement que le serveur (nom normalisé du lieu égal à un libellé normalisé).

/** @param {string} texte @returns {string} texte sans accents ni ponctuation, en minuscules */
export function normaliserNom(texte) {
  return (texte ?? "")
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

/**
 * @param {{identifiant: string, nom: string|null}[]} lieux lieux rattachés à la salle
 * @param {string[]} libelles libellés choisis
 * @returns {string[]} identifiants des lieux dont le nom correspond à un libellé
 */
export function lieuxCorrespondants(lieux, libelles) {
  const noms = new Set(libelles.map(normaliserNom));
  return lieux
    .filter((l) => l.nom && noms.has(normaliserNom(l.nom)))
    .map((l) => l.identifiant);
}
