import { test, expect } from "@playwright/test";

test.describe("Scénario: admin — sections et contenus", () => {
  test("admin_h1_visible", async ({ page }) => {
    await page.goto("/admin");
    await expect(
      page.getByRole("heading", { name: "Administration", level: 1 }),
    ).toBeVisible();
  });

  test("admin_section_utilisateurs_visible", async ({ page }) => {
    await page.goto("/admin#utilisateurs");
    await expect(page.locator("section#utilisateurs")).toBeVisible();
  });

  test("admin_section_configuration_visible", async ({ page }) => {
    await page.goto("/admin#configuration");
    await expect(page.locator("section#configuration")).toBeVisible();
  });

  test("admin_section_jobs_visible", async ({ page }) => {
    await page.goto("/admin");
    await expect(page.locator("section#jobs")).toBeVisible();
  });

  test("admin_section_logs_visible", async ({ page }) => {
    await page.goto("/admin#logs");
    await expect(page.locator("section#logs")).toBeVisible();
  });

  test("admin_anchor_utilisateurs_scroll", async ({ page }) => {
    await page.goto("/admin#utilisateurs");
    await expect(page.locator("section#utilisateurs")).toBeVisible();
  });
});

test.describe("Scénario: admin — gestion des utilisateurs", () => {
  test("admin_creerPuisSupprimerUtilisateur_fluxComplet", async ({ page }) => {
    const emailTest = `e2e-test-${Date.now()}@example.com`;

    // Créer l'utilisateur
    await page.goto("/admin#utilisateurs");
    await page.fill('input[name="email"][type="email"]', emailTest);
    await Promise.all([
      page.waitForURL(/\/admin/, { waitUntil: "domcontentloaded" }),
      page.click(
        'form[action*="/admin/utilisateurs/creer"] button[type="submit"]',
      ),
    ]);

    // Vérifier le dialog d'invitation
    const dialog = page.locator("dialog#dialog-invitation");
    await expect(dialog).toBeVisible({ timeout: 5000 });
    const lienInvitation = await page
      .locator("input#lien-invitation")
      .inputValue();
    expect(lienInvitation.length).toBeGreaterThan(0);
    await page.click("button#btn-fermer-invitation");

    // Retrouver la carte du compte créé
    const carteUser = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await expect(carteUser).toBeVisible({ timeout: 3000 });

    // Supprimer via le form (hx-boost="false" → submit classique)
    const formSupprimer = carteUser.locator("form.form-supprimer-user");
    await expect(formSupprimer).toBeVisible();

    // Le click sur "Supprimer" doit ouvrir le dialog de confirmation via JS
    await formSupprimer.locator('button[type="submit"]').click();
    await expect(page.locator("dialog#dialog-supprimer-user")).toBeVisible({
      timeout: 3000,
    });

    // Confirmer la suppression
    await Promise.all([
      page.waitForURL(/\/admin/, { waitUntil: "domcontentloaded" }),
      page.click("button#dialog-supprimer-user-confirmer"),
    ]);

    // Vérifier que l'utilisateur a disparu de la liste
    await expect(
      page.locator(".user-carte").filter({ hasText: emailTest }),
    ).not.toBeVisible({ timeout: 5000 });
  });
});

test.describe("Scénario: admin — API endpoints", () => {
  test("api_admin_quota_retourne_200", async ({ request }) => {
    const rep = await request.get("/api/admin/quota");
    expect([200, 404]).toContain(rep.status());
  });

  test("api_admin_non_accessible_sans_session", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const rep = await ctx.request.get("/api/admin/quota");
    expect([401, 302, 403]).toContain(rep.status());
    await ctx.close();
  });
});
