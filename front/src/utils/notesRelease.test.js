import { describe, expect, it } from "vitest";
import {
  estPreVersion,
  formaterNotes,
  NOTES_PRE_VERSION,
  trouverEntree,
} from "./notesRelease.js";

const versions = [
  {
    entete: "v 3.2.0 — 17 novembre 2026",
    categories: [
      {
        categorie: "Application",
        items: ["Première phrase.", "Seconde phrase."],
      },
      { categorie: "Accueil", items: ["Une phrase."] },
    ],
  },
  { entete: "v 3.1.5 — 16 novembre 2026", categories: [] },
];

describe("notesRelease", () => {
  it("trouve l'entrée d'une version et ignore les autres", () => {
    expect(trouverEntree(versions, "3.1.5").entete).toContain("3.1.5");
    expect(trouverEntree(versions, "3.2.0").entete).toContain("3.2.0");
  });

  it("ne confond pas deux versions dont l'une préfixe l'autre", () => {
    expect(trouverEntree(versions, "3.2")).toBeUndefined();
    expect(trouverEntree(versions, "3.2.0.1")).toBeUndefined();
  });

  it("renvoie undefined pour une version absente", () => {
    expect(trouverEntree(versions, "9.9.9")).toBeUndefined();
  });

  it("formate une section par catégorie, une puce par phrase", () => {
    expect(formaterNotes(versions[0])).toBe(
      "## Application\n\n- Première phrase.\n- Seconde phrase.\n\n## Accueil\n\n- Une phrase.\n",
    );
  });

  it("reconnaît une pré-version à son suffixe", () => {
    expect(estPreVersion("3.2.0-dev.1")).toBe(true);
    expect(estPreVersion("3.2.0-rc.2")).toBe(true);
    expect(estPreVersion("3.2.0")).toBe(false);
    expect(estPreVersion("3.2")).toBe(false);
  });

  it("donne aux pré-versions une note fixe, jamais un changelog inventé", () => {
    expect(NOTES_PRE_VERSION).toBe(
      "Pré-version d'essai, non destinée à la production.\n",
    );
  });
});
