import { describe, expect, test } from "vitest";
import { formaterResultatTestLieu } from "./lieuEventim.js";

describe("formaterResultatTestLieu", () => {
  test("formaterResultatTestLieu_avantTout_test_retourneVide", () => {
    expect(formaterResultatTestLieu(null)).toBe("");
  });

  test("formaterResultatTestLieu_injoignable_signaleLEchec", () => {
    expect(formaterResultatTestLieu({ injoignable: true })).toBe(
      "Eventim injoignable ✗",
    );
  });

  test("formaterResultatTestLieu_unConcert_resteAuSingulier", () => {
    expect(formaterResultatTestLieu({ nbConcerts: 1 })).toBe("✓ 1 concert");
  });

  test("formaterResultatTestLieu_plusieursConcerts_metAuPluriel", () => {
    expect(formaterResultatTestLieu({ nbConcerts: 12 })).toBe("✓ 12 concerts");
  });

  test("formaterResultatTestLieu_aucunConcert_leDit", () => {
    expect(formaterResultatTestLieu({ nbConcerts: 0 })).toBe("✓ aucun concert");
  });
});
