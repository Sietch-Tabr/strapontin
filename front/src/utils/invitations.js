// Libellés et règles d'affichage des invitations envoyées par un utilisateur.

const LIBELLES_STATUT = {
  en_attente: "En attente",
  activee: "Activée",
  expiree: "Expirée",
  annulee: "Annulée",
};

/** Libellé lisible d'un statut d'invitation ; un statut inconnu est rendu tel quel. */
export function libelleStatut(statut) {
  return LIBELLES_STATUT[statut] ?? statut;
}

/** Seule une invitation en attente peut être renvoyée ou annulée. */
export function peutAgir(statut) {
  return statut === "en_attente";
}

/** Phrase qui dit combien d'invitations il reste, lisible avant la saisie. */
export function libelleRestantes(restantes, plafond) {
  if (plafond === 0) {
    return "L'envoi d'invitations est désactivé pour votre compte.";
  }
  if (restantes <= 0) {
    return "Vous avez atteint le nombre maximal d'invitations en attente. Annulez-en une ou attendez qu'elle soit activée ou expire.";
  }
  return restantes === 1
    ? "Il vous reste 1 invitation à envoyer."
    : `Il vous reste ${restantes} invitations à envoyer.`;
}

/** L'invitation peut être saisie si le compte a encore une invitation disponible. */
export function peutInviter(restantes, plafond) {
  return plafond > 0 && restantes > 0;
}
