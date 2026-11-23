import { describe, test, expect } from "vitest";
import { estNavigationReelle } from "./navigationReelle.js";

const DEPART = { path: "/", matched: [] };

describe("estNavigationReelle", () => {
  test("estNavigationReelle_resolutionDeLaRouteInitiale_neRelitRien", () => {
    expect(estNavigationReelle({ path: "/spectacles" }, DEPART)).toBe(false);
    expect(estNavigationReelle({ path: "/" }, DEPART)).toBe(false);
  });

  test("estNavigationReelle_changementDePage_relit", () => {
    const depuis = { path: "/spectacles", matched: [{}] };
    expect(estNavigationReelle({ path: "/decouverte" }, depuis)).toBe(true);
  });

  test("estNavigationReelle_memePageAutreRequete_neRelitRien", () => {
    const depuis = { path: "/decouverte", matched: [{}] };
    expect(estNavigationReelle({ path: "/decouverte" }, depuis)).toBe(false);
  });
});
