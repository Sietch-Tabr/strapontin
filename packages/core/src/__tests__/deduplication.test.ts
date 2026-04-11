import { describe, it, expect } from "vitest";
import type { ConcertBrut } from "@strapontin/sources";
import { dedoublonner } from "../deduplication.js";

function concert(
  champs: Pick<ConcertBrut, "artiste" | "date" | "salle"> &
    Partial<ConcertBrut>,
): ConcertBrut {
  return {
    heure: null,
    ville: null,
    dept: null,
    prixMin: null,
    prixMax: null,
    url: null,
    source: "ticketmaster",
    classification: null,
    genre: null,
    statut: null,
    ...champs,
  };
}

describe("dedoublonner — cas limites", () => {
  it("retourne une liste vide inchangée", () => {
    expect(dedoublonner([])).toEqual([]);
  });

  it("retourne un concert unique tel quel", () => {
    const concerts = [concert({ artiste: "Radiohead", date: "2025-06-15", salle: "Zénith Paris" })];
    expect(dedoublonner(concerts)).toHaveLength(1);
  });

  it("préserve tous les champs du concert retourné", () => {
    const c = concert({
      artiste: "Radiohead",
      date: "2025-06-15",
      salle: "Zénith Paris",
      ville: "Paris",
      dept: "75",
      heure: "20:00",
      prixMin: 35,
      prixMax: 65,
      url: "https://example.com",
      source: "ticketmaster",
      classification: "music",
      genre: "Rock",
    });
    const resultat = dedoublonner([c]);
    expect(resultat[0]).toMatchObject({
      artiste: "Radiohead",
      ville: "Paris",
      prixMin: 35,
      url: "https://example.com",
    });
  });
});

describe("dedoublonner — passe exacte", () => {
  it("fusionne deux concerts identiques (même artiste/date/ville) de sources différentes", () => {
    const concerts = [
      concert({ artiste: "Beyoncé", date: "2025-07-10", salle: "Stade de France", ville: "Paris", source: "ticketmaster" }),
      concert({ artiste: "Beyoncé", date: "2025-07-10", salle: "Stade de France", ville: "Paris", source: "eventim" }),
    ];
    expect(dedoublonner(concerts)).toHaveLength(1);
  });

  it("ne fusionne pas deux artistes différents le même jour dans la même ville", () => {
    const concerts = [
      concert({ artiste: "Radiohead", date: "2025-06-15", salle: "Zénith Paris", ville: "Paris" }),
      concert({ artiste: "Coldplay", date: "2025-06-15", salle: "Accor Arena", ville: "Paris" }),
    ];
    expect(dedoublonner(concerts)).toHaveLength(2);
  });

  it("choisit le concert le plus complet lors d'une fusion", () => {
    const complet = concert({
      artiste: "Daft Punk",
      date: "2025-08-20",
      salle: "Zénith Paris",
      ville: "Paris",
      url: "https://example.com",
      prixMin: 45,
      heure: "21:00",
      source: "eventim",
    });
    const incomplet = concert({
      artiste: "Daft Punk",
      date: "2025-08-20",
      salle: "Zénith Paris",
      ville: "Paris",
      source: "ticketmaster",
    });
    const resultat = dedoublonner([incomplet, complet]);
    expect(resultat).toHaveLength(1);
    expect(resultat[0]!.url).toBe("https://example.com");
    expect(resultat[0]!.prixMin).toBe(45);
  });

  it("préfère le nom avec diacritiques à complétude égale", () => {
    const avecAccent = concert({
      artiste: "Julien Doré",
      date: "2025-09-01",
      salle: "Olympia",
      ville: "Paris",
      source: "eventim",
    });
    const sansAccent = concert({
      artiste: "Julien Dore",
      date: "2025-09-01",
      salle: "Olympia",
      ville: "Paris",
      source: "ticketmaster",
    });
    const resultat = dedoublonner([sansAccent, avecAccent]);
    expect(resultat).toHaveLength(1);
    expect(resultat[0]!.artiste).toBe("Julien Doré");
  });
});

describe("dedoublonner — passe fuzzy", () => {
  it("fusionne deux noms légèrement différents (sim >= 0.85) même date/ville", () => {
    // "julien clerc" vs "julien clere" — distance 1, sim ~0.917
    const concerts = [
      concert({ artiste: "Julien Clerc", date: "2025-10-05", salle: "Casino de Paris", ville: "Paris", source: "ticketmaster" }),
      concert({ artiste: "Julien Clere", date: "2025-10-05", salle: "Casino de Paris", ville: "Paris", source: "bandsintown" }),
    ];
    expect(dedoublonner(concerts)).toHaveLength(1);
  });

  it("ne fusionne pas deux artistes distincts malgré même date/ville", () => {
    const concerts = [
      concert({ artiste: "Radiohead", date: "2025-11-12", salle: "Accor Arena", ville: "Paris", source: "ticketmaster" }),
      concert({ artiste: "Coldplay", date: "2025-11-12", salle: "Stade de France", ville: "Paris", source: "eventim" }),
    ];
    expect(dedoublonner(concerts)).toHaveLength(2);
  });
});

describe("dedoublonner — fallback salle", () => {
  it("fusionne deux concerts avec villes divergentes mais même salle", () => {
    // Stade de France : TM donne 'Paris', Eventim donne 'Saint-Denis'
    const concerts = [
      concert({ artiste: "Beyoncé", date: "2025-06-15", salle: "Stade de France", ville: "Paris", source: "ticketmaster" }),
      concert({ artiste: "Beyoncé", date: "2025-06-15", salle: "Stade de France", ville: "Saint-Denis", source: "eventim" }),
    ];
    expect(dedoublonner(concerts)).toHaveLength(1);
  });

  it("ne fusionne pas via le fallback salle si les artistes sont trop différents", () => {
    const concerts = [
      concert({ artiste: "Radiohead", date: "2025-06-15", salle: "Stade de France", ville: "Paris", source: "ticketmaster" }),
      concert({ artiste: "Coldplay", date: "2025-06-15", salle: "Stade de France", ville: "Saint-Denis", source: "eventim" }),
    ];
    expect(dedoublonner(concerts)).toHaveLength(2);
  });
});

describe("dedoublonner — artiste sans ville", () => {
  it("gère les concerts sans ville (ville null)", () => {
    const concerts = [
      concert({ artiste: "Portishead", date: "2025-05-20", salle: "La Cigale", ville: null, source: "bandsintown" }),
      concert({ artiste: "Portishead", date: "2025-05-20", salle: "La Cigale", ville: null, source: "ticketmaster" }),
    ];
    // ville null → ville_norm "" → même clé → fusionné
    expect(dedoublonner(concerts)).toHaveLength(1);
  });

  it("ne fusionne pas deux concerts sans ville si salles différentes", () => {
    const concerts = [
      concert({ artiste: "Portishead", date: "2025-05-20", salle: "La Cigale", ville: null, source: "bandsintown" }),
      concert({ artiste: "Portishead", date: "2025-05-21", salle: "Olympia", ville: null, source: "ticketmaster" }),
    ];
    expect(dedoublonner(concerts)).toHaveLength(2);
  });
});
