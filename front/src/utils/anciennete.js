/// Ancienneté d'une donnée gardée, en français courant, pour dire à l'utilisateur ce qu'il lit.

const MINUTE = 60 * 1000;
const HEURE = 60 * MINUTE;
const JOUR = 24 * HEURE;

/// @param instantMs date d'enregistrement de la donnée (ms)
/// @param maintenantMs date courante (ms)
/// @return « à l'instant », « il y a 12 min », « il y a 3 h » ou « il y a 2 jours »
export function formaterAnciennete(instantMs, maintenantMs) {
  const ecart = Math.max(0, maintenantMs - instantMs);
  if (ecart < MINUTE) return "à l'instant";
  if (ecart < HEURE) return `il y a ${Math.floor(ecart / MINUTE)} min`;
  if (ecart < JOUR) return `il y a ${Math.floor(ecart / HEURE)} h`;
  const jours = Math.floor(ecart / JOUR);
  return `il y a ${jours} jour${jours > 1 ? "s" : ""}`;
}
