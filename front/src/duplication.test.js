// Contrôle automatique de la duplication du front : deux fichiers ne partagent pas plus de
// PLAFOND_PAIRE fenêtres de 9 lignes identiques, sauf les paires déjà connues, plafonnées à leur
// valeur actuelle (un plafond ne se relève jamais, il se baisse à chaque extraction). Une
// duplication nouvelle doit devenir un composant ou un composable partagé.
import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const RACINE = join(import.meta.dirname, ".");
const FENETRE = 9;
const PLAFOND_PAIRE = 8;

// Paires connues et leur plafond (mesuré à la création du contrôle, 3.1.4).
const PLAFONDS_CONNUS = {
  "pages/DecouvertePage.vue|pages/SpectaclesPage.vue": 17,
  "pages/ArtistesPage.vue|pages/SallesPage.vue": 11,
};

function sources(dossier) {
  return readdirSync(dossier).flatMap((nom) => {
    const chemin = join(dossier, nom);
    if (statSync(chemin).isDirectory()) return sources(chemin);
    return /\.(vue|js)$/.test(nom) && !nom.endsWith(".test.js") ? [chemin] : [];
  });
}

function fenetresPartagees() {
  const index = new Map();
  for (const fichier of sources(RACINE)) {
    const lignes = readFileSync(fichier, "utf8")
      .split("\n")
      .map((l) => l.trim());
    for (let i = 0; i + FENETRE <= lignes.length; i++) {
      const fenetre = lignes.slice(i, i + FENETRE);
      if (fenetre.filter((l) => l.length > 2).length < FENETRE - 1) continue;
      const cle = fenetre.join("\n");
      if (!index.has(cle)) index.set(cle, new Set());
      index.get(cle).add(relative(RACINE, fichier));
    }
  }
  const paires = new Map();
  for (const fichiers of index.values()) {
    const liste = [...fichiers].sort();
    for (let a = 0; a < liste.length; a++) {
      for (let b = a + 1; b < liste.length; b++) {
        const cle = `${liste[a]}|${liste[b]}`;
        paires.set(cle, (paires.get(cle) ?? 0) + 1);
      }
    }
  }
  return paires;
}

describe("duplication du front", () => {
  it("aucune paire de fichiers ne dépasse son plafond de fenêtres identiques", () => {
    const depassements = [...fenetresPartagees()]
      .filter(([paire, n]) => n > (PLAFONDS_CONNUS[paire] ?? PLAFOND_PAIRE))
      .map(([paire, n]) => `${paire} : ${n} fenêtres`);
    expect(depassements).toEqual([]);
  });
});
