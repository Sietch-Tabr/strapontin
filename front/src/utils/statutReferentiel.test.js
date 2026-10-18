import { describe, expect, test } from "vitest";
import {
  classeStatut,
  decrireSuspicion,
  interpreterSuspicion,
  libelleStatut,
} from "./statutReferentiel.js";

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

describe("interpreterSuspicion", () => {
  test("interpreterSuspicion_sallesAvecConcerts_parleDIdentifiantPerime", () => {
    expect(interpreterSuspicion(3)).toContain("3 concerts à venir");
    expect(interpreterSuspicion(3)).toContain("probablement périmé");
  });

  test("interpreterSuspicion_unSeulConcert_resteAuSingulier", () => {
    expect(interpreterSuspicion(1)).toContain("1 concert à venir");
  });

  test("interpreterSuspicion_sansConcert_distingueLeCasSansEvenement", () => {
    expect(interpreterSuspicion(0)).toContain("aucun concert à venir");
    expect(interpreterSuspicion(0)).not.toContain("périmé");
  });
});

describe("decrireSuspicion", () => {
  test("decrireSuspicion_plusieursFetchs_nommeSourceCompteurEtDate", () => {
    expect(
      decrireSuspicion(
        { source: "ticketmaster", nbVides: 4, depuis: "2026-10-12T04:10:00Z" },
        () => "le 12 oct.",
      ),
    ).toBe(
      "ticketmaster : 4 fetchs consécutifs sans résultat, depuis le 12 oct.",
    );
  });

  test("decrireSuspicion_unSeulFetch_resteAuSingulier", () => {
    expect(
      decrireSuspicion(
        { source: "eventim", nbVides: 1, depuis: "x" },
        () => "hier",
      ),
    ).toBe("eventim : 1 fetch consécutif sans résultat, depuis hier");
  });
});
