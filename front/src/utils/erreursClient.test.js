import { describe, expect, test } from "vitest";
import {
  construireCorpsErreurJs,
  construireCorpsErreurPromise,
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
