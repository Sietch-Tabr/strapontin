// Modèle de communication d'incident de sécurité : rédigé à l'avance, il évite d'omettre un
// élément attendu sous la pression, alors que l'email envoyé ne peut plus être corrigé.

export const MODELE_TITRE_INCIDENT = "Incident de sécurité sur Strapontin";

export const MODELE_MESSAGE_INCIDENT = `Le [date], nous avons détecté [nature de l'incident].

Données concernées : [ce qui a pu être exposé, ou « aucune donnée personnelle »].

Ce que nous avons fait : [mesures prises].

Ce que nous vous demandons : [action attendue, par exemple définir un nouveau mot de passe via « Mot de passe oublié »].

Pour toute question : [adresse de contact].`;

/// Champs du modèle encore à compléter (texte entre crochets).
export function champsACompleter(...textes) {
  return textes.flatMap((texte) => texte.match(/\[[^\]]+\]/g) ?? []);
}
