/// Message affiché pour un département choisi où aucun concert n'est connu : une absence de
/// couverture de nos billetteries (outre-mer, vendu sur des billetteries locales) ne se présente
/// pas comme une absence de concerts.

const MOTIF_OUTRE_MER = /^9[78]\d$/;

/// @param departements codes des départements choisis sans aucun concert
/// @return messages à afficher, un par cas : outre-mer d'abord, métropole ensuite
export function messagesCouverture(departements) {
  const outreMer = departements.filter((d) => MOTIF_OUTRE_MER.test(d));
  const metropole = departements.filter((d) => !MOTIF_OUTRE_MER.test(d));
  const messages = [];
  if (outreMer.length) {
    const pluriel = outreMer.length > 1;
    messages.push({
      cle: "outre-mer",
      texte:
        `Aucun concert n'est affiché pour ${outreMer.join(", ")} : ` +
        `${pluriel ? "ces départements ne sont" : "ce département n'est"} pas couvert` +
        `${pluriel ? "s" : ""} par nos billetteries (Ticketmaster, Eventim), ` +
        "alors que leurs concerts se vendent surtout sur des billetteries locales. " +
        "L'absence de concert ici ne veut pas dire qu'il n'y en a pas.",
    });
  }
  if (metropole.length) {
    messages.push({
      cle: "metropole",
      texte:
        "Aucun concert n'est proposé pour l'instant par nos sources " +
        `(Ticketmaster, Eventim) dans : ${metropole.join(", ")}.`,
    });
  }
  return messages;
}

/// @return phrase affichée sous une salle suivie qu'aucune de nos sources ne couvre
export const MESSAGE_SALLE_NON_COUVERTE =
  "Cette salle n'est couverte par aucune de nos sources (Ticketmaster, Eventim, sites de salles) : " +
  "ses concerts n'apparaîtront pas ici, sans que cela prouve qu'elle n'en propose pas.";
