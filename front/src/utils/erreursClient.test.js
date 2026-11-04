import { describe, expect, test } from "vitest";
import {
  construireCorpsErreurJs,
  construireCorpsErreurPromise,
  lireContexteClient,
} from "./erreursClient.js";

describe("construireCorpsErreurJs", () => {
  test("construireCorpsErreurJs_parametresComplets_retourneCorpsJsError", () => {
    expect(
      construireCorpsErreurJs("Erreur X", "/assets/index.js", 42, 7),
    ).toEqual({
      type: "js-error",
      message: "Erreur X",
      url: "/assets/index.js",
      ligne: 42,
      colonne: 7,
    });
  });

  test("construireCorpsErreurJs_messageAbsent_retourneChaineVide", () => {
    expect(construireCorpsErreurJs(undefined, "", 0, 0).message).toBe("");
  });
});

describe("erreur masquée par le navigateur", () => {
  test("construireCorpsErreurJs_scriptErrorSansOrigine_estMarqueeMasquee", () => {
    expect(
      construireCorpsErreurJs("Script error.", "", 0, 0, null).masquee,
    ).toBe(true);
  });

  test("construireCorpsErreurJs_scriptErrorAvecAdresse_neEstPasMasquee", () => {
    expect(
      construireCorpsErreurJs("Script error.", "/assets/a.js", 3, 4, null)
        .masquee,
    ).toBeUndefined();
  });

  test("construireCorpsErreurJs_erreurAvecObjetError_neEstPasMasquee", () => {
    expect(
      construireCorpsErreurJs("Script error.", "", 0, 0, new Error("x"))
        .masquee,
    ).toBeUndefined();
  });
});

describe("lireContexteClient", () => {
  const env = {
    location: { pathname: "/decouverte", search: "?q=jazz", hash: "#haut" },
    navigator: { onLine: false, standalone: true },
    document: { visibilityState: "hidden" },
  };

  test("lireContexteClient_fenetreComplete_decritLaPageEtLesConditions", () => {
    expect(lireContexteClient(env)).toEqual({
      page: "/decouverte?q=jazz#haut",
      modeAffichage: "pwa",
      enLigne: false,
      visibilite: "hidden",
    });
  });

  test("construireCorpsErreurJs_contexteFourni_estJointAuCorps", () => {
    const corps = construireCorpsErreurJs(
      "Erreur X",
      "/a.js",
      1,
      2,
      undefined,
      lireContexteClient(env),
    );

    expect(corps).toMatchObject({
      page: "/decouverte?q=jazz#haut",
      enLigne: false,
    });
  });

  test("construireCorpsErreurPromise_contexteFourni_estJointAuCorps", () => {
    const corps = construireCorpsErreurPromise(
      "échec",
      "/aide",
      lireContexteClient(env),
    );

    expect(corps.modeAffichage).toBe("pwa");
  });
});

describe("construireCorpsErreurPromise", () => {
  test("construireCorpsErreurPromise_erreurAvecMessage_extraitLeMessage", () => {
    const corps = construireCorpsErreurPromise(
      new Error("boom"),
      "https://strapontin.kwaite.fr/spectacles",
    );

    expect(corps).toMatchObject({
      type: "promise-rejection",
      message: "boom",
      url: "https://strapontin.kwaite.fr/spectacles",
    });
  });

  test("construireCorpsErreurPromise_raisonNonErreur_convertitEnChaine", () => {
    expect(construireCorpsErreurPromise("échec réseau", "/aide").message).toBe(
      "échec réseau",
    );
  });

  test("construireCorpsErreurPromise_raisonNulle_retourneChaineVide", () => {
    expect(construireCorpsErreurPromise(null, "/aide").message).toBe("");
  });
});

describe("pile d'appel", () => {
  test("construireCorpsErreurPromise_erreurAvecPile_joindNomEtPile", () => {
    const erreur = new Error("The object can not be found here.");
    erreur.name = "NotFoundError";

    const corps = construireCorpsErreurPromise(erreur, "/parametres");

    expect(corps.nom).toBe("NotFoundError");
    expect(corps.pile).toContain("The object can not be found here.");
  });

  test("construireCorpsErreurPromise_pileTresLongue_estTronquee", () => {
    const erreur = new Error("x");
    erreur.stack = "a".repeat(5000);

    expect(construireCorpsErreurPromise(erreur, "/").pile).toHaveLength(2000);
  });

  test("construireCorpsErreurJs_erreurFournie_joindLaPile", () => {
    const corps = construireCorpsErreurJs("m", "u.js", 1, 2, new Error("m"));

    expect(corps.pile).toContain("Error: m");
  });

  test("construireCorpsErreurPromise_raisonNonErreur_neJointPasDePile", () => {
    expect(construireCorpsErreurPromise("échec", "/").pile).toBeUndefined();
  });
});
