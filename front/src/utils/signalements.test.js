import { describe, expect, it } from "vitest";
import {
  actionsSignalement,
  decrireType,
  formaterDetails,
  libelleEntite,
} from "./signalements.js";

describe("decrireType", () => {
  const catalogue = [
    { type: "doublon_salle", libelle: "Doublon de salle", explication: "x" },
  ];

  it("renvoie le libellé et l'explication du catalogue", () => {
    expect(decrireType(catalogue, "doublon_salle")).toEqual({
      libelle: "Doublon de salle",
      explication: "x",
    });
  });

  it("se replie sur l'identifiant pour un type non décrit", () => {
    expect(decrireType(catalogue, "inconnu")).toEqual({
      libelle: "inconnu",
      explication: "",
    });
    expect(decrireType(undefined, "inconnu").libelle).toBe("inconnu");
  });
});

describe("libelleEntite", () => {
  it("nomme l'entité avec son genre", () => {
    expect(libelleEntite({ type: "salle", nom: "Zénith" })).toBe(
      "Salle : Zénith",
    );
  });

  it("n'affiche que le genre sans nom", () => {
    expect(libelleEntite({ type: "invariant", nom: null })).toBe("Données");
    expect(libelleEntite({ type: "autre" })).toBe("Élément");
  });
});

describe("formaterDetails", () => {
  it("décompose un objet JSON en couples lisibles", () => {
    expect(
      formaterDetails('{"distanceMetres": 3200, "lieux": ["a", "b"]}'),
    ).toEqual([
      { cle: "distance metres", valeur: "3200" },
      { cle: "lieux", valeur: "a, b" },
    ]);
  });

  it("rend tel quel un texte non JSON", () => {
    expect(formaterDetails("autre nom|nom")).toEqual([
      { cle: "", valeur: "autre nom|nom" },
    ]);
  });

  it("ignore l'absence de détails", () => {
    expect(formaterDetails(null)).toEqual([]);
  });
});

describe("actionsSignalement", () => {
  it("propose répartition, faux positif et fiche pour une contamination", () => {
    expect(
      actionsSignalement({
        categorie: "contamination_salle",
        type: "contamination_salle",
        entite: { type: "salle", id: "1", nom: "Zénith" },
      }),
    ).toEqual(["repartir", "fauxPositif", "voirFiche"]);
  });

  it("propose d'ignorer l'écart d'un artiste à vérifier", () => {
    expect(
      actionsSignalement({
        categorie: "necessite_intervention",
        type: "necessite_intervention",
        entite: { type: "artiste", id: "2", nom: "-M-" },
      }),
    ).toEqual(["ignorerEcart", "voirFiche"]);
  });

  it("se limite à la résolution pour un contrôle global", () => {
    expect(
      actionsSignalement({
        categorie: "nouveau",
        type: "orphelins_fk",
        entite: { type: "invariant", id: "3", nom: null },
      }),
    ).toEqual(["resoudre"]);
  });

  it("propose la répartition d'une salle aux lieux dispersés", () => {
    expect(
      actionsSignalement({
        categorie: "nouveau",
        type: "dispersion_sources_salle",
        entite: { type: "salle", id: "4", nom: "Comédie" },
      }),
    ).toEqual(["repartir", "resoudre", "voirFiche"]);
  });
});
