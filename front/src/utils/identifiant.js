// Consignes de saisie de l'identifiant et du mot de passe, mêmes règles que le serveur
// (RegleIdentifiant côté web) : affichées sous chaque champ, jamais en placeholder.

export const IDENTIFIANT_MIN = 3;
export const IDENTIFIANT_MAX = 30;
export const IDENTIFIANT_MOTIF = "[a-zA-Z0-9._\\-]+";
export const AIDE_IDENTIFIANT =
  "De 3 à 30 caractères : lettres, chiffres, points, tirets ou tirets bas (_).";

export const MOT_DE_PASSE_MIN = 12;
export const AIDE_MOT_DE_PASSE = "Au moins 12 caractères.";
