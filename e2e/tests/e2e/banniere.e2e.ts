import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

async function publierBanniere(
  page: import("@playwright/test").Page,
  message: string,
  type: string = "info",
) {
  await page.request.post("/api/admin/banniere", {
    data: { message, type },
  });
}

async function effacerBanniere(page: import("@playwright/test").Page) {
  await page.request.delete("/api/admin/banniere");
}

test.describe("Bannière système overlay", () => {
  test.afterEach(async ({ page }) => {
    await effacerBanniere(page);
  });

  test("banniere_apparait_apres_publication_admin", async ({ page }) => {
    await publierBanniere(page, "Test bannière E2E", "info");
    await page.goto("/");
    await expect(page.locator(".banniere-overlay")).toBeVisible({
      timeout: 5000,
    });
    await expect(page.locator(".banniere-message")).toContainText(
      "Test bannière E2E",
    );
  });

  test("banniere_hors_flux_ne_decale_pas_contenu", async ({ page }) => {
    await publierBanniere(page, "Test décalage", "info");
    await page.goto("/");
    const overlay = page.locator(".banniere-overlay");
    await expect(overlay).toBeVisible({ timeout: 5000 });
    const position = await overlay.evaluate((el) => {
      const s = getComputedStyle(el);
      return s.position;
    });
    expect(position).toBe("fixed");
  });

  test("banniere_fermeture_manuelle_masque_definitivement", async ({
    page,
  }) => {
    await publierBanniere(page, "Test fermeture manuelle", "error");
    await page.goto("/");
    await expect(page.locator(".banniere-overlay")).toBeVisible({
      timeout: 5000,
    });
    await page.locator(".banniere-fermer").click();
    await expect(page.locator(".banniere-overlay")).toBeHidden({
      timeout: 2000,
    });
    await page.goto("/spectacles");
    await expect(page.locator(".banniere-overlay")).toBeHidden({
      timeout: 2000,
    });
  });

  test("banniere_reapparait_a_la_navigation", async ({ page }) => {
    await publierBanniere(page, "Test réapparition nav", "warning");
    await page.goto("/");
    const overlay = page.locator(".banniere-overlay");
    await expect(overlay).toBeVisible({ timeout: 5000 });
    await page.goto("/spectacles");
    await page.waitForLoadState("domcontentloaded");
    await expect(overlay).toBeVisible({ timeout: 5000 });
  });

  test("banniere_desktop_positionnee_apres_sidebar", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await publierBanniere(page, "Test position desktop", "info");
    await page.goto("/");
    const overlay = page.locator(".banniere-overlay");
    await expect(overlay).toBeVisible({ timeout: 5000 });
    const left = await overlay.evaluate((el) =>
      parseFloat(getComputedStyle(el).left),
    );
    expect(left).toBeGreaterThan(0);
  });

  test("banniere_mobile_pleine_largeur", async ({ page, isMobile }) => {
    if (!isMobile) test.skip();
    await publierBanniere(page, "Test position mobile", "info");
    await page.goto("/");
    const overlay = page.locator(".banniere-overlay");
    await expect(overlay).toBeVisible({ timeout: 5000 });
    const left = await overlay.evaluate((el) =>
      parseFloat(getComputedStyle(el).left),
    );
    expect(left).toBe(0);
  });

  test("banniere_progression_presente_pour_type_info", async ({ page }) => {
    await publierBanniere(page, "Test progression", "info");
    await page.goto("/");
    await expect(page.locator(".banniere-overlay")).toBeVisible({
      timeout: 5000,
    });
    await expect(page.locator(".banniere-progression")).toBeVisible();
  });

  test("banniere_erreur_sans_barre_progression", async ({ page }) => {
    await publierBanniere(page, "Test erreur sans progression", "error");
    await page.goto("/");
    await expect(page.locator(".banniere-overlay")).toBeVisible({
      timeout: 5000,
    });
    await expect(page.locator(".banniere-progression")).not.toBeVisible();
  });
});
