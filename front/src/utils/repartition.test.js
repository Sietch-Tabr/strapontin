import { describe, it, expect } from "vitest";
import { lieuxCorrespondants, normaliserNom } from "./repartition.js";

describe("normaliserNom", () => {
  it("retire accents, ponctuation et casse", () => {
    expect(normaliserNom("Comédie de Paris !")).toBe("comedie de paris");
  });
});

describe("lieuxCorrespondants", () => {
  const lieux = [
    { identifiant: "ticketmaster:rZ1", nom: "Comédie de Paris" },
    {
      identifiant: "eventim:internal:comedie de paris",
      nom: "comedie de paris",
    },
    { identifiant: "ticketmaster:rZ2", nom: null },
    { identifiant: "ticketmaster:rZ3", nom: "La Comédie Bastille" },
  ];

  it("retient les lieux de toutes sources dont le nom porte un libellé choisi", () => {
    expect(lieuxCorrespondants(lieux, ["Comedie de Paris"])).toEqual([
      "ticketmaster:rZ1",
      "eventim:internal:comedie de paris",
    ]);
  });

  it("ignore un lieu sans nom connu", () => {
    expect(lieuxCorrespondants(lieux, [""])).toEqual([]);
  });
});
