import { describe, expect, test } from "vitest";
import {
  champsACompleter,
  MODELE_MESSAGE_INCIDENT,
  MODELE_TITRE_INCIDENT,
} from "./modeleIncident.js";

describe("champsACompleter", () => {
  test("champsACompleter_modeleIntact_listeTousLesChamps", () => {
    expect(
      champsACompleter(MODELE_TITRE_INCIDENT, MODELE_MESSAGE_INCIDENT),
    ).toEqual([
      "[date]",
      "[nature de l'incident]",
      "[ce qui a pu être exposé, ou « aucune donnée personnelle »]",
      "[mesures prises]",
      "[action attendue, par exemple définir un nouveau mot de passe via « Mot de passe oublié »]",
      "[adresse de contact]",
    ]);
  });

  test("champsACompleter_messageComplete_aucunChamp", () => {
    expect(
      champsACompleter("Titre", "Le 2 octobre, accès non autorisé."),
    ).toEqual([]);
  });
});
