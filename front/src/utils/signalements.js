/// Présentation des signalements de l'onglet « À traiter » : nature du problème en clair,
/// entité nommée et actions possibles sur place.

const LIBELLES_ENTITE = {
  salle: "Salle",
  artiste: "Artiste",
  utilisateur: "Utilisateur",
  evenement: "Concert",
  invariant: "Données",
};

// Signalements dont l'action attendue est la suppression du concert désigné.
const TYPES_CONCERT_A_SUPPRIMER = new Set([
  "date_festival_ecartee",
  "concert_double_salles_distinctes",
]);

// Signalements de salle qu'une répartition des concerts par libellé source peut corriger.
const TYPES_REPARTISSABLES = new Set([
  "contamination_salle",
  "dispersion_sources_salle",
  "salle_multi_departements",
  "salle_suivie_contaminee",
]);

/// @param catalogue liste `{type, libelle, explication}` renvoyée par l'API
/// @param type      identifiant technique du signalement
/// @return `{libelle, explication}`, l'identifiant lui-même si le type n'est pas décrit
export function decrireType(catalogue, type) {
  const entree = (catalogue ?? []).find((t) => t.type === type);
  return entree
    ? { libelle: entree.libelle, explication: entree.explication }
    : { libelle: type, explication: "" };
}

/// @param entite `{type, id, nom}` du signalement
/// @return libellé lisible : « Salle : Zénith de Paris », « Données » pour un contrôle global
export function libelleEntite(entite) {
  const genre = LIBELLES_ENTITE[entite?.type] ?? "Élément";
  return entite?.nom ? `${genre} : ${entite.nom}` : genre;
}

/// Détails JSON d'un signalement en couples lisibles ; un texte non JSON est rendu tel quel.
/// @param details chaîne JSON, texte libre ou null
/// @return liste de `{cle, valeur, lien?}` ; `lien` est renseigné pour une URL ou un identifiant
///         d'élément Wikidata, que l'interface ouvre dans un nouvel onglet
export function formaterDetails(details) {
  if (!details) return [];
  let valeur;
  try {
    valeur = JSON.parse(details);
  } catch {
    return [{ cle: "", valeur: details }];
  }
  if (valeur === null || typeof valeur !== "object" || Array.isArray(valeur)) {
    return [{ cle: "", valeur: versTexte(valeur) }];
  }
  return Object.entries(valeur).map(([cle, v]) => {
    const couple = {
      cle: cle.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase(),
      valeur: versTexte(v),
    };
    const lien = lienDeValeur(v);
    return lien ? { ...couple, lien } : couple;
  });
}

const MOTIF_URL = /^https?:\/\/\S+$/;
const MOTIF_ELEMENT_WIKIDATA = /^Q\d+$/;

// Seuls les schémas http(s) deviennent des liens : une valeur de signalement vient de sources
// externes et ne doit jamais produire un `javascript:`.
function lienDeValeur(v) {
  if (typeof v !== "string") return null;
  if (MOTIF_URL.test(v)) return v;
  if (MOTIF_ELEMENT_WIKIDATA.test(v))
    return `https://www.wikidata.org/wiki/${v}`;
  return null;
}

function versTexte(v) {
  if (Array.isArray(v)) return v.map(versTexte).join(", ");
  if (v !== null && typeof v === "object") return JSON.stringify(v);
  return String(v);
}

/// Actions proposées sur place pour un signalement, dans l'ordre d'affichage.
/// @param signalement entrée renvoyée par l'API
/// @return codes parmi `comparer`, `aligner`, `supprimerConcert`, `resoudre`, `fauxPositif`,
///         `sallesDistinctes`, `ignorerEcart`, `repartir`, `voirFiche`
export function actionsSignalement(signalement) {
  const actions = [];
  const entite = signalement.entite ?? {};
  if (signalement.autreSalle && entite.type === "salle") {
    actions.push("comparer");
  }
  if (TYPES_REPARTISSABLES.has(signalement.type) && entite.type === "salle") {
    actions.push("repartir");
  }
  if (signalement.type === "position_source_eloignee") {
    actions.push("aligner");
  }
  if (
    TYPES_CONCERT_A_SUPPRIMER.has(signalement.type) &&
    entite.type === "evenement"
  ) {
    actions.push("supprimerConcert");
  }
  switch (signalement.categorie) {
    case "contamination_salle":
      actions.push("fauxPositif");
      break;
    case "doublon_salle":
      actions.push("sallesDistinctes");
      break;
    case "necessite_intervention":
      actions.push("ignorerEcart");
      break;
    default:
      actions.push("resoudre");
  }
  if ((entite.type === "salle" || entite.type === "artiste") && entite.nom) {
    actions.push("voirFiche");
  }
  return actions;
}
