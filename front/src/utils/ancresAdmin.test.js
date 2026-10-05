import { describe, it, expect } from "vitest";
import { redirectionAncreAdmin } from "./ancresAdmin.js";

describe("redirectionAncreAdmin", () => {
  it("redirige les signalements et concerts vers À traiter", () => {
    for (const hash of ["#signalements", "#concerts"]) {
      expect(
        redirectionAncreAdmin({ path: "/admin", hash, query: {} }),
      ).toEqual({
        path: "/admin",
        hash: "#a-traiter",
        query: {},
        replace: true,
      });
    }
  });

  it("redirige salles et artistes vers le Référentiel avec l'entité", () => {
    expect(
      redirectionAncreAdmin({ path: "/admin", hash: "#artistes", query: {} }),
    ).toEqual({
      path: "/admin",
      hash: "#referentiel",
      query: { entite: "artiste" },
      replace: true,
    });
  });

  it("conserve la recherche déjà présente dans l'URL", () => {
    expect(
      redirectionAncreAdmin({
        path: "/admin",
        hash: "#salles",
        query: { recherche: "olympia" },
      }).query,
    ).toEqual({ recherche: "olympia", entite: "salle" });
  });

  it("ne touche ni un onglet actuel ni une autre page", () => {
    expect(
      redirectionAncreAdmin({ path: "/admin", hash: "#a-traiter", query: {} }),
    ).toBeNull();
    expect(
      redirectionAncreAdmin({ path: "/admin", hash: "", query: {} }),
    ).toBeNull();
    expect(
      redirectionAncreAdmin({
        path: "/parametres",
        hash: "#salles",
        query: {},
      }),
    ).toBeNull();
  });
});
