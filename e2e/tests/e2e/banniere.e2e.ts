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
    // v-if : l'élément est retiré du DOM après la transition leave (200ms).
    // toBeHidden ne suffit pas car opacity:0 reste "visible" pour Playwright.
    await expect(page.locator(".banniere-overlay")).not.toBeAttached({
      timeout: 3000,
    });
    await page.goto("/spectacles");
    await expect(page.locator(".banniere-overlay")).not.toBeAttached({
      timeout: 3000,
    });
  });

  test("banniere_reapparait_a_la_navigation", async ({ page }) => {
    await publierBanniere(page, "Test réapparition nav", "warning");
    await page.goto("/");
    const overlay = page.locator(".banniere-overlay");
    await expect(overlay).toBeVisible({ timeout: 5000 });
    // Navigation SPA via lien (Vue Router afterEach) plutôt que rechargement complet —
    // évite la dépendance au timing d'initialisation Vue sur webkit.
    await page.locator('a[href="/spectacles"]').first().click();
    await expect(page).toHaveURL(/\/spectacles/);
    await expect(overlay).toBeVisible({ timeout: 5000 });
  });

  // #834 : l'auto-masquage (expiration de la minuterie, 10s pour "info") ne doit bloquer la
  // réapparition que pour la navigation courante — contrairement à
  // banniere_fermeture_manuelle_masque_definitivement ci-dessus, une navigation SPA suivante doit
  // réafficher la bannière (Design-et-navigation.md : réapparaît à chaque changement de page,
  // sauf fermeture manuelle). Avant correctif, idAutoMasque bloquait aussi la réapparition à la
  // navigation, exactement comme une fermeture manuelle.
  test("banniere_reapparaitApresAutoMasquage_a_la_navigation_suivante", async ({
    page,
  }) => {
    await publierBanniere(page, "Test auto-masquage puis réapparition", "info");
    await page.goto("/");
    const overlay = page.locator(".banniere-overlay");
    await expect(overlay).toBeVisible({ timeout: 5000 });

    // Minuterie "info" = 10s (useBanniere.js DELAIS.info).
    await expect(overlay).not.toBeAttached({ timeout: 12000 });

    await page.locator('a[href="/spectacles"]').first().click();
    await expect(page).toHaveURL(/\/spectacles/);
    await expect(overlay).toBeVisible({ timeout: 5000 });
  });

  // #880 : seul le minuteur "info" (10s) était laissé expirer naturellement — le minuteur
  // "warning" (20s, useBanniere.js DELAIS.warning) n'était jamais testé jusqu'à expiration.
  test("banniere_typeWarning_autoMasquageApres20s_puisReapparaitALaNavigation", async ({
    page,
  }) => {
    await publierBanniere(page, "Test auto-masquage warning", "warning");
    await page.goto("/");
    const overlay = page.locator(".banniere-overlay");
    await expect(overlay).toBeVisible({ timeout: 5000 });

    // Minuterie "warning" = 20s (useBanniere.js DELAIS.warning).
    await expect(overlay).not.toBeAttached({ timeout: 22000 });

    await page.locator('a[href="/spectacles"]').first().click();
    await expect(page).toHaveURL(/\/spectacles/);
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

  // #852 : l'onglet admin n'affichait jamais l'état courant de la bannière.
  test("admin_banniere_onglet_affiche_etat_publie", async ({ page }) => {
    await publierBanniere(page, "Test onglet admin publié", "warning");
    await page.goto("/admin#communication");
    await expect(page.locator("section#communication")).toContainText(
      "Test onglet admin publié",
    );
    await expect(page.locator("section#communication")).toContainText(
      "warning",
    );
  });

  test("admin_banniere_onglet_affiche_aucune_banniere_publiee", async ({
    page,
  }) => {
    await effacerBanniere(page);
    await page.goto("/admin#communication");
    await expect(page.locator("section#communication")).toContainText(
      "Aucune bannière publiée",
    );
  });

  test("admin_banniere_publierViaFormulaire_rafraichitEtatAffiche", async ({
    page,
  }) => {
    await effacerBanniere(page);
    await page.goto("/admin#communication");
    await page.locator("#banniere-message").fill("Test formulaire admin");
    await page.locator("#banniere-type").selectOption("error");
    await page.getByRole("button", { name: "Publier", exact: true }).click();
    await expect(page.locator("section#communication")).toContainText(
      "Test formulaire admin",
    );
    await expect(page.locator("section#communication")).toContainText("error");
  });

  test("admin_banniere_effacerViaFormulaire_rafraichitEtatAffiche", async ({
    page,
  }) => {
    await publierBanniere(page, "Test avant effacement", "info");
    await page.goto("/admin#communication");
    await expect(page.locator("section#communication")).toContainText(
      "Test avant effacement",
    );
    await page.getByRole("button", { name: "Effacer" }).click();
    await expect(page.locator("section#communication")).toContainText(
      "Aucune bannière publiée",
    );
  });
});
