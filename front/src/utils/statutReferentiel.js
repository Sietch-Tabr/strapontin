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

/// Texte d'aide des statuts d'une salle, affiché au-dessus du référentiel.
export const AIDE_STATUTS_SALLES =
  "« À jour » : aucune source suspecte. « À vérifier » : une source n'a rien renvoyé lors de " +
  "3 fetchs consécutifs, depuis moins de 24 h. « Invalide » : depuis plus de 24 h. Un fetch " +
  "qui renvoie des concerts, ou la vérification nocturne qui retrouve l'identifiant, lève le statut.";

/// Texte d'aide des statuts d'un artiste, affiché au-dessus du référentiel.
export const AIDE_STATUTS_ARTISTES =
  "« À jour » : rien à signaler. « Invalide » : le nom publié par Ticketmaster ne correspond " +
  "plus à l'artiste suivi (date indiquée) ; corriger son identifiant ou accepter ce nom.";

/// Ce que dit une suspicion sur la salle : une salle sans concert à venir est « à vérifier » par
/// construction, sans que l'identifiant soit forcément périmé.
/// @param nbConcerts nombre de concerts à venir de la salle
/// @return phrase d'interprétation
export function interpreterSuspicion(nbConcerts) {
  return nbConcerts > 0
    ? `La salle a ${nbConcerts} concert${nbConcerts > 1 ? "s" : ""} à venir alors que la source ` +
        "n'en renvoie plus : l'identifiant est probablement périmé."
    : "La salle n'a aucun concert à venir : la source peut simplement n'avoir rien à renvoyer.";
}

/// @param suspicion `{source, nbVides, depuis}` d'une source suspecte
/// @param formaterDate fonction qui met en forme l'instant ISO `depuis`
/// @return « ticketmaster : 4 fetchs consécutifs sans résultat, depuis … »
export function decrireSuspicion(suspicion, formaterDate) {
  const pluriel = suspicion.nbVides > 1 ? "s" : "";
  return (
    `${suspicion.source} : ${suspicion.nbVides} fetch${pluriel} consécutif${pluriel} ` +
    `sans résultat, depuis ${formaterDate(suspicion.depuis)}`
  );
}
