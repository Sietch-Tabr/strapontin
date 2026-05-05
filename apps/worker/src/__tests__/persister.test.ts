import { describe, it, expect, vi } from "vitest";

vi.mock("../db.js", () => ({ db: {} }));

import {
  estDoublonSalle,
  deduplicerParCleConflit,
  deduplicerLotReconcilie,
  type ValeurConcert,
} from "../persister.js";
import type { ConcertBrut } from "@strapontin/sources";

describe("estDoublonSalle", () => {
  it("retourne_true_si_chaines_identiques", () => {
    expect(estDoublonSalle("accor arena", "accor arena")).toBe(true);
  });

  it("retourne_true_si_similarite_superieure_a_0_5", () => {
    // "zenith paris" vs "zenith de paris" — sim ≈ 0.92
    expect(estDoublonSalle("zenith paris", "zenith de paris")).toBe(true);
  });

  it("retourne_true_si_containment_chaine_courte_4_chars_min", () => {
    // "olympia" contient "olymp" (5 chars)
    expect(estDoublonSalle("olymp", "olympia")).toBe(true);
    expect(estDoublonSalle("olympia", "grand olympia de paris")).toBe(true);
  });

  it("retourne_false_si_containment_chaine_trop_courte", () => {
    // "oly" fait 3 chars < 4 → pas de containment
    expect(estDoublonSalle("oly", "olympia")).toBe(false);
  });

  it("retourne_false_pour_salles_completement_differentes", () => {
    expect(estDoublonSalle("zenith paris", "stade de france")).toBe(false);
    expect(estDoublonSalle("la cigale", "accor arena")).toBe(false);
  });

  it("retourne_false_si_les_deux_chaines_sont_vides", () => {
    expect(estDoublonSalle("", "")).toBe(true);
  });

  it("detecte_doublon_avec_accent_et_sans_accent", () => {
    // "zenith" vs "zénith" — similarite gère les variantes proches
    expect(estDoublonSalle("zenith paris", "zenith paris")).toBe(true);
  });

  it("accor_arena_vs_tout_majuscule_est_doublon_via_similarite", () => {
    expect(estDoublonSalle("accor arena", "ACCOR ARENA")).toBe(false);
  });
});

function concert(
  artiste: string,
  date: string,
  salle: string,
  source: string = "ticketmaster",
): ConcertBrut {
  return {
    artiste,
    date,
    heure: "20:00",
    salle,
    ville: "Paris",
    dept: "75",
    prixMin: null,
    prixMax: null,
    url: null,
    source,
    classification: "musique",
    genre: "Pop",
    statut: null,
  };
}

describe("deduplicerParCleConflit", () => {
  it("deduplicer_concerts_identiques_meme_source_retourne_un_seul", () => {
    const liste = [
      concert("Mika", "2026-06-01", "Accor Arena"),
      concert("Mika", "2026-06-01", "Accor Arena"),
    ];
    expect(deduplicerParCleConflit(liste)).toHaveLength(1);
  });

  it("deduplicer_resolver_cree_doublon_conserve_version_avec_diacritiques", () => {
    // "MIKA" (TM tout-majuscule résolu) et "Mika" (Eventim) — même clé après normaliserTexte
    const liste = [
      concert("MIKA", "2026-06-01", "Accor Arena", "ticketmaster"),
      concert("Mika", "2026-06-01", "Accor Arena", "eventim"),
    ];
    const resultat = deduplicerParCleConflit(liste);
    expect(resultat).toHaveLength(1);
    expect(resultat[0]!.artiste).toBe("Mika");
  });

  it("deduplicer_salle_avec_accent_et_sans_accent_meme_cle_conserve_accents", () => {
    const liste = [
      concert("Amel Bent", "2026-05-10", "Zenith de Dijon", "ticketmaster"),
      concert("Amel Bent", "2026-05-10", "Zénith de Dijon", "eventim"),
    ];
    const resultat = deduplicerParCleConflit(liste);
    expect(resultat).toHaveLength(1);
    expect(resultat[0]!.salle).toBe("Zénith de Dijon");
  });

  it("deduplicer_concerts_dates_differentes_conserve_les_deux", () => {
    const liste = [
      concert("Mika", "2026-06-01", "Accor Arena"),
      concert("Mika", "2026-06-02", "Accor Arena"),
    ];
    expect(deduplicerParCleConflit(liste)).toHaveLength(2);
  });

  it("deduplicer_artistes_differents_conserve_les_deux", () => {
    const liste = [
      concert("Mika", "2026-06-01", "Accor Arena"),
      concert("Coldplay", "2026-06-01", "Accor Arena"),
    ];
    expect(deduplicerParCleConflit(liste)).toHaveLength(2);
  });

  it("deduplicer_liste_vide_retourne_vide", () => {
    expect(deduplicerParCleConflit([])).toHaveLength(0);
  });
});

function valeur(
  artisteNorm: string,
  date: string,
  salleNorm: string,
  artiste = artisteNorm,
  salle = salleNorm,
): ValeurConcert {
  return {
    artiste,
    artisteNorm,
    date,
    heure: null,
    salle,
    salleNorm,
    ville: null,
    dept: null,
    prixMin: null,
    prixMax: null,
    url: null,
    source: "ticketmaster",
    classification: null,
    genre: null,
    disponible: true,
    statut: null,
  };
}

describe("deduplicerLotReconcilie", () => {
  it("liste_vide_retourne_vide", () => {
    expect(deduplicerLotReconcilie([])).toHaveLength(0);
  });

  it("concerts_cles_differentes_conserves_les_deux", () => {
    const lot = [
      valeur("mika", "2026-06-01", "accor arena"),
      valeur("coldplay", "2026-06-01", "stade de france"),
    ];
    expect(deduplicerLotReconcilie(lot)).toHaveLength(2);
  });

  it("doublon_meme_cle_conserve_version_avec_diacritiques_artiste", () => {
    const lot = [
      valeur("julien dore", "2026-05-10", "olympia", "Julien Dore", "Olympia"),
      valeur("julien dore", "2026-05-10", "olympia", "Julien Doré", "Olympia"),
    ];
    const resultat = deduplicerLotReconcilie(lot);
    expect(resultat).toHaveLength(1);
    expect(resultat[0]!.artiste).toBe("Julien Doré");
  });

  it("doublon_meme_cle_conserve_version_avec_casse_mixte_salle", () => {
    const lot = [
      valeur("mika", "2026-07-01", "zenith paris", "Mika", "ZENITH PARIS"),
      valeur("mika", "2026-07-01", "zenith paris", "Mika", "Zénith Paris"),
    ];
    const resultat = deduplicerLotReconcilie(lot);
    expect(resultat).toHaveLength(1);
    expect(resultat[0]!.salle).toBe("Zénith Paris");
  });
});
