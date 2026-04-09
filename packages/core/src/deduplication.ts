// Dédoublonnage inter-sources — portage de src/merge.py du CLI Python.
//
// Deux passes :
// 1. Exacte : (artiste_norm, date, ville_norm)
// 2. Fuzzy  : (date, ville_norm) + similarité artiste ≥ 0.85
// Fallback  : (date, salle_norm) pour les grandes salles avec villes divergentes

import type { ConcertBrut } from "@strapontin/sources";
import { normaliserTexte, normaliserVille, similarite } from "./normalisation.js";

const SEUIL_SIMILARITE = 0.85;

interface ConcertAvecNormes extends ConcertBrut {
  _artisteNorm: string;
  _villeNorm: string;
  _salleNorm: string;
}

function enrichir(concert: ConcertBrut): ConcertAvecNormes {
  return {
    ...concert,
    _artisteNorm: normaliserTexte(concert.artiste),
    _villeNorm: concert.ville ? normaliserVille(concert.ville) : "",
    _salleNorm: normaliserTexte(concert.salle),
  };
}

// Choisit le meilleur représentant parmi les doublons (favorise celui avec le plus d'infos)
function fusionner(concerts: ConcertAvecNormes[]): ConcertAvecNormes {
  return concerts.reduce((meilleur, candidat) => {
    const scoreM = scoreCompletude(meilleur);
    const scoreC = scoreCompletude(candidat);
    return scoreC > scoreM ? candidat : meilleur;
  });
}

function scoreCompletude(c: ConcertAvecNormes): number {
  return (
    (c.heure ? 1 : 0) +
    (c.ville ? 1 : 0) +
    (c.dept ? 1 : 0) +
    (c.prixMin !== null ? 1 : 0) +
    (c.url ? 1 : 0)
  );
}

export function dedoublonner(concerts: ConcertBrut[]): ConcertBrut[] {
  if (concerts.length === 0) return [];

  const enrichis = concerts.map(enrichir);

  // --- Passe 1 : exacte ---
  // Clé : artiste_norm + date + ville_norm
  const groupesExacts = new Map<string, ConcertAvecNormes[]>();

  for (const concert of enrichis) {
    const cle = `${concert._artisteNorm}|${concert.date}|${concert._villeNorm}`;
    const groupe = groupesExacts.get(cle) ?? [];
    groupe.push(concert);
    groupesExacts.set(cle, groupe);
  }

  const apresPasseExacte: ConcertAvecNormes[] = [];
  for (const groupe of groupesExacts.values()) {
    apresPasseExacte.push(fusionner(groupe));
  }

  // --- Passe 2 : fuzzy ---
  // Regrouper par (date, ville_norm) puis comparer les artistes
  const groupesDateVille = new Map<string, ConcertAvecNormes[]>();

  for (const concert of apresPasseExacte) {
    const cle = `${concert.date}|${concert._villeNorm}`;
    const groupe = groupesDateVille.get(cle) ?? [];
    groupe.push(concert);
    groupesDateVille.set(cle, groupe);
  }

  const apresPasseFuzzy: ConcertAvecNormes[] = [];

  for (const groupe of groupesDateVille.values()) {
    if (groupe.length <= 1) {
      apresPasseFuzzy.push(...groupe);
      continue;
    }

    // Algorithme greedy : fusionner les concerts dont l'artiste est similaire ≥ seuil
    const fusionnes: ConcertAvecNormes[][] = [];
    const traites = new Set<number>();

    for (let i = 0; i < groupe.length; i++) {
      if (traites.has(i)) continue;
      const sousGroupe: ConcertAvecNormes[] = [groupe[i]!];
      traites.add(i);

      for (let j = i + 1; j < groupe.length; j++) {
        if (traites.has(j)) continue;
        const sim = similarite(groupe[i]!._artisteNorm, groupe[j]!._artisteNorm);
        if (sim >= SEUIL_SIMILARITE) {
          sousGroupe.push(groupe[j]!);
          traites.add(j);
        }
      }

      fusionnes.push(sousGroupe);
    }

    for (const sg of fusionnes) {
      apresPasseFuzzy.push(fusionner(sg));
    }
  }

  // --- Fallback : (date, salle_norm) ---
  // Pour les grandes salles où la ville diverge entre sources
  const groupesDateSalle = new Map<string, ConcertAvecNormes[]>();

  for (const concert of apresPasseFuzzy) {
    const cle = `${concert.date}|${concert._salleNorm}`;
    const groupe = groupesDateSalle.get(cle) ?? [];
    groupe.push(concert);
    groupesDateSalle.set(cle, groupe);
  }

  const resultat: ConcertBrut[] = [];

  for (const groupe of groupesDateSalle.values()) {
    if (groupe.length <= 1) {
      const { _artisteNorm: _a, _villeNorm: _v, _salleNorm: _s, ...concert } = groupe[0]!;
      resultat.push(concert);
      continue;
    }

    // Vérifier similarité artiste avant de fusionner sur salle
    const fusionnes: ConcertAvecNormes[][] = [];
    const traites = new Set<number>();

    for (let i = 0; i < groupe.length; i++) {
      if (traites.has(i)) continue;
      const sousGroupe: ConcertAvecNormes[] = [groupe[i]!];
      traites.add(i);

      for (let j = i + 1; j < groupe.length; j++) {
        if (traites.has(j)) continue;
        const sim = similarite(groupe[i]!._artisteNorm, groupe[j]!._artisteNorm);
        if (sim >= SEUIL_SIMILARITE) {
          sousGroupe.push(groupe[j]!);
          traites.add(j);
        }
      }

      fusionnes.push(sousGroupe);
    }

    for (const sg of fusionnes) {
      const meilleur = fusionner(sg);
      const { _artisteNorm: _a, _villeNorm: _v, _salleNorm: _s, ...concert } = meilleur;
      resultat.push(concert);
    }
  }

  return resultat;
}
