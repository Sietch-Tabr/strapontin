import { describe, expect, test } from "vitest";
import { etatRecherche } from "./consigneRecherche.js";

describe("etatRecherche", () => {
  test("etatRecherche_saisieVide_autoriseLeRetourALaListeComplete", () => {
    expect(etatRecherche("")).toEqual({ peutLancer: true, explication: "" });
    expect(etatRecherche(null).peutLancer).toBe(true);
  });

  test("etatRecherche_unSeulCaractere_bloqueEtExpliquePourquoi", () => {
    const etat = etatRecherche(" a ");
    expect(etat.peutLancer).toBe(false);
    expect(etat.explication).toContain("Encore 1 caractère");
  });

  test("etatRecherche_deuxCaracteres_autorise", () => {
    expect(etatRecherche("ab")).toEqual({ peutLancer: true, explication: "" });
  });

  test("etatRecherche_espacesSeuls_comptentCommeVide", () => {
    expect(etatRecherche("   ").peutLancer).toBe(true);
  });
});
