// Situation d'un concert à confirmer, telle que la renvoie l'API admin : ce que les billetteries
// en savent, pour décider sans chercher fiche par fiche.
const SITUATIONS = {
  jumeau: {
    libelle: "Même produit à une autre date",
    classe: "badge badge-info",
  },
  annoncee_ailleurs: {
    libelle: "Annoncé par une autre billetterie",
    classe: "badge badge-avertissement",
  },
  introuvable: {
    libelle: "Introuvable partout",
    classe: "badge badge-danger",
  },
};

const INCONNUE = { libelle: "Inconnue", classe: "badge" };

export function libelleSituation(situation) {
  return (SITUATIONS[situation] ?? INCONNUE).libelle;
}

export function classeSituation(situation) {
  return (SITUATIONS[situation] ?? INCONNUE).classe;
}
