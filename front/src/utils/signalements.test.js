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

  it("fait d'une URL http(s) un lien", () => {
    expect(
      formaterDetails('{"url": "https://www.eventim.fr/event/x-1/"}'),
    ).toEqual([
      {
        cle: "url",
        valeur: "https://www.eventim.fr/event/x-1/",
        lien: "https://www.eventim.fr/event/x-1/",
      },
    ]);
  });

  it("ne fait jamais un lien d'un autre schéma", () => {
    const [couple] = formaterDetails('{"url": "javascript:alert(1)"}');
    expect(couple.lien).toBeUndefined();
  });

  it("renvoie vers Wikidata pour un identifiant d'élément", () => {
    const [couple] = formaterDetails('{"wikidataId": "Q123456"}');
    expect(couple.lien).toBe("https://www.wikidata.org/wiki/Q123456");
  });

  it("ne lie pas un texte qui ressemble seulement à un identifiant", () => {
    const [couple] = formaterDetails('{"nom": "Q123 Arena"}');
    expect(couple.lien).toBeUndefined();
  });
});

describe("actionsSignalement", () => {
  it("propose la suppression d'un concert attribué à tort", () => {
    expect(
      actionsSignalement({
        categorie: "nouveau",
        type: "date_festival_ecartee",
        entite: { type: "evenement", id: "3", nom: "Selah Sue · 16/10/2026" },
      }),
    ).toEqual(["supprimerConcert", "resoudre"]);
  });

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

  it("propose de comparer deux salles quand le signalement en nomme une seconde", () => {
    expect(
      actionsSignalement({
        categorie: "doublon_salle",
        type: "doublon_salle",
        entite: { type: "salle", id: "a", nom: "Zénith" },
        autreSalle: { type: "salle", id: "b", nom: "Zénith de Paris" },
      }),
    ).toEqual(["comparer", "sallesDistinctes", "voirFiche"]);
  });
});
