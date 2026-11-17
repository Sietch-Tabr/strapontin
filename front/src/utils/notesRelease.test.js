import { describe, expect, it } from "vitest";
import { formaterNotes, trouverEntree } from "./notesRelease.js";

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
});
