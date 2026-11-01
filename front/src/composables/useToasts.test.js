import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { etatConnexion } from "./useEtatConnexion.js";
import { afficherToast, useToasts } from "./useToasts.js";

describe("afficherToast : écran hors ligne sans données", () => {
  beforeEach(() => {
    etatConnexion.sansDonnees = false;
    useToasts().toasts.value.length = 0;
  });

  afterEach(() => {
    etatConnexion.sansDonnees = false;
  });

  test("afficherToast_erreurDeChargementSansDonnees_nAfficheRien", () => {
    etatConnexion.sansDonnees = true;

    const id = afficherToast("Impossible de charger les concerts.", "erreur");

    expect(id).toBeNull();
    expect(useToasts().toasts.value).toHaveLength(0);
  });

  test("afficherToast_perteDeConnexionSansDonnees_resteAffichee", () => {
    etatConnexion.sansDonnees = true;

    const id = afficherToast("Vous êtes hors ligne.", "erreur", {
      toujours: true,
    });

    expect(id).not.toBeNull();
    expect(useToasts().toasts.value).toHaveLength(1);
  });

  test("afficherToast_erreurAvecDonnees_estAffichee", () => {
    afficherToast("Impossible de charger les concerts.", "erreur");

    expect(useToasts().toasts.value).toHaveLength(1);
  });

  test("afficherToast_infoSansDonnees_estAffichee", () => {
    etatConnexion.sansDonnees = true;

    afficherToast("Connexion rétablie.", "succes");

    expect(useToasts().toasts.value).toHaveLength(1);
  });
});
