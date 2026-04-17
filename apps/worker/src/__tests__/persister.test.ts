import { describe, it, expect, vi } from "vitest";

vi.mock("../db.js", () => ({ db: {} }));

import { estDoublonSalle } from "../persister.js";

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
