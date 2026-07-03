// Mapping statut → classe CSS / libellé, partagé entre AdminSalles.vue et AdminArtistes.vue
// (référentiel salles/artistes, statuts alimentés par ReconciliationNocturneService côté worker).

export function classeStatut(statut) {
  if (statut === "invalide") return "badge-quota-critique";
  if (statut === "a_verifier") return "badge-quota-attention";
  return "";
}

export function libelleStatut(statut) {
  if (statut === "a_jour") return "À jour";
  if (statut === "a_verifier") return "À vérifier";
  if (statut === "invalide") return "Invalide";
  return statut;
}
