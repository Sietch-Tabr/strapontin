import { describe, expect, test } from "vitest";
import { repliSansDroitAdmin } from "./gardeAdmin.js";

describe("repliSansDroitAdmin", () => {
  const routeAdmin = { meta: { admin: true } };

  test("repliSansDroitAdmin_utilisateurSimpleSurRouteAdmin_renvoieLAccueil", () => {
    expect(repliSansDroitAdmin(routeAdmin, { role: "user" })).toBe("/");
  });

  test("repliSansDroitAdmin_administrateurSurRouteAdmin_laisseUnAcces", () => {
    expect(repliSansDroitAdmin(routeAdmin, { role: "admin" })).toBeUndefined();
  });

  test("repliSansDroitAdmin_routeOrdinaire_laisseUnAcces", () => {
    expect(repliSansDroitAdmin({ meta: {} }, { role: "user" })).toBeUndefined();
    expect(repliSansDroitAdmin({}, { role: "user" })).toBeUndefined();
  });

  test("repliSansDroitAdmin_sessionInconnue_laisseLaGardeDeConnexion", () => {
    expect(repliSansDroitAdmin(routeAdmin, null)).toBeUndefined();
  });
});
