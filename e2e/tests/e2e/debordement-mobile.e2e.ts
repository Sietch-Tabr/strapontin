import { test, expect, type Page } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

// Largeur d'un petit téléphone, imposée à tous les navigateurs : un tableau resté en largeur
// naturelle fait défiler toute la page horizontalement.
test.use({
  storageState: FICHIER_AUTH_ADMIN,
  viewport: { width: 360, height: 740 },
});

async function attendreChargement(page: Page) {
  await expect(page.locator("main")).toBeVisible();
  await expect(page.locator(".etat-chargement")).toHaveCount(0, {
    timeout: 15000,
  });
}

async function largeurExcedentaire(page: Page) {
  return page.evaluate(
    () =>
      document.documentElement.scrollWidth -
      document.documentElement.clientWidth,
  );
}

test.describe("Scénario: aucun débordement horizontal sur mobile", () => {
  for (const chemin of [
    "/",
    "/spectacles",
    "/decouverte",
    "/artistes",
    "/salles",
  ]) {
    test(`page_${chemin}_tientDansLaLargeur`, async ({ page }) => {
      await page.goto(chemin);
      await attendreChargement(page);
      expect(await largeurExcedentaire(page)).toBeLessThanOrEqual(0);
    });
  }

  for (const onglet of [
    "#jobs",
    "#a-traiter",
    "#sources",
    "#logs",
    "#configuration",
    "#communication",
    "#utilisateurs",
  ]) {
    test(`admin_${onglet}_tientDansLaLargeur`, async ({ page }) => {
      await page.goto(`/admin${onglet}`);
      await attendreChargement(page);
      expect(await largeurExcedentaire(page)).toBeLessThanOrEqual(0);
    });
  }

  for (const entite of ["salle", "artiste"]) {
    test(`admin_referentiel_${entite}_tientDansLaLargeur`, async ({ page }) => {
      await page.goto(`/admin?entite=${entite}#referentiel`);
      await attendreChargement(page);
      expect(await largeurExcedentaire(page)).toBeLessThanOrEqual(0);
    });
  }

  test("admin_referentielSalles_dialogueLieuEventim_tientDansLaLargeur", async ({
    page,
  }) => {
    await page.goto("/admin?entite=salle#referentiel");
    await attendreChargement(page);
    await page.getByRole("button", { name: "Lieu Eventim" }).first().click();
    await expect(page.getByRole("dialog")).toBeVisible();
    expect(await largeurExcedentaire(page)).toBeLessThanOrEqual(0);
  });
});
