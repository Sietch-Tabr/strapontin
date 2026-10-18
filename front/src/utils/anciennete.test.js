import { describe, expect, test } from "vitest";
import { formaterAnciennete } from "./anciennete.js";

const T0 = 1_700_000_000_000;

describe("formaterAnciennete", () => {
  test("formaterAnciennete_moinsDUneMinute_disALInstant", () => {
    expect(formaterAnciennete(T0, T0 + 30_000)).toBe("à l'instant");
  });

  test("formaterAnciennete_minutes_arrondiALaMinuteInferieure", () => {
    expect(formaterAnciennete(T0, T0 + 12 * 60_000 + 59_000)).toBe(
      "il y a 12 min",
    );
  });

  test("formaterAnciennete_heures_donneLesHeures", () => {
    expect(formaterAnciennete(T0, T0 + 3 * 3_600_000)).toBe("il y a 3 h");
  });

  test("formaterAnciennete_jours_accordeAuPluriel", () => {
    expect(formaterAnciennete(T0, T0 + 86_400_000)).toBe("il y a 1 jour");
    expect(formaterAnciennete(T0, T0 + 2 * 86_400_000)).toBe("il y a 2 jours");
  });

  test("formaterAnciennete_horlogeEnArriere_nEstJamaisNegative", () => {
    expect(formaterAnciennete(T0 + 5_000, T0)).toBe("à l'instant");
  });
});
