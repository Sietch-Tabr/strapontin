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

    expect(corps).toEqual({
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
