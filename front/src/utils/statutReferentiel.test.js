import { describe, expect, test } from "vitest";
import { classeStatut, libelleStatut } from "./statutReferentiel.js";

describe("classeStatut", () => {
  test("classeStatut_invalide_retourneBadgeCritique", () => {
    expect(classeStatut("invalide")).toBe("badge-quota-critique");
  });

  test("classeStatut_aVerifier_retourneBadgeAttention", () => {
    expect(classeStatut("a_verifier")).toBe("badge-quota-attention");
  });

  test("classeStatut_aJour_retourneChaineVide", () => {
    expect(classeStatut("a_jour")).toBe("");
  });

  test("classeStatut_statutInconnu_retourneChaineVide", () => {
    expect(classeStatut("autre")).toBe("");
  });
});

describe("libelleStatut", () => {
  test("libelleStatut_aJour_retourneLibelleAJour", () => {
    expect(libelleStatut("a_jour")).toBe("À jour");
  });

  test("libelleStatut_aVerifier_retourneLibelleAVerifier", () => {
    expect(libelleStatut("a_verifier")).toBe("À vérifier");
  });

  test("libelleStatut_invalide_retourneLibelleInvalide", () => {
    expect(libelleStatut("invalide")).toBe("Invalide");
  });

  test("libelleStatut_statutInconnu_retourneStatutBrut", () => {
    expect(libelleStatut("autre")).toBe("autre");
  });
});
