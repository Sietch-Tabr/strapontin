import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

test.describe("Scénario: admin — sections et contenus", () => {
  test("admin_h1_visible", async ({ page }) => {
    await page.goto("/admin");
    await expect(
      page.getByRole("heading", { name: "Administration", level: 1 }),
    ).toBeVisible();
  });

  test("admin_section_jobs_visible_au_chargement", async ({ page }) => {
    await page.goto("/admin");
    await expect(page.locator("section#jobs")).toBeVisible();
  });

  test("admin_desktop_section_utilisateurs_visible", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#utilisateurs"]').click();
    await expect(page.locator("section#utilisateurs")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_desktop_section_configuration_visible", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#configuration"]').click();
    await expect(page.locator("section#configuration")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_desktop_section_logs_visible", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#logs"]').click();
    await expect(page.locator("section#logs")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_desktop_section_banniere_visible", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#banniere"]').click();
    await expect(page.locator("section#banniere")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_desktop_retour_jobs_depuis_logs", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#logs"]').click();
    await page.locator('a[href="#jobs"]').click();
    await expect(page.locator("section#jobs")).toBeVisible();
    await expect(page.locator("section#logs")).not.toBeVisible();
  });

  test("admin_mobile_select_navigation_logs", async ({ page, isMobile }) => {
    if (!isMobile) test.skip();
    await page.goto("/admin");
    await page.selectOption("select.param-onglets-mobile", "#logs");
    await expect(page.locator("section#logs")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_mobile_select_navigation_configuration", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/admin");
    await page.selectOption("select.param-onglets-mobile", "#configuration");
    await expect(page.locator("section#configuration")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_mobile_select_navigation_banniere", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/admin");
    await page.selectOption("select.param-onglets-mobile", "#banniere");
    await expect(page.locator("section#banniere")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_mobile_select_navigation_utilisateurs", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/admin");
    await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
    await expect(page.locator("section#utilisateurs")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_mobile_retour_jobs_depuis_logs", async ({ page, isMobile }) => {
    if (!isMobile) test.skip();
    await page.goto("/admin");
    await page.selectOption("select.param-onglets-mobile", "#logs");
    await page.selectOption("select.param-onglets-mobile", "#jobs");
    await expect(page.locator("section#jobs")).toBeVisible();
    await expect(page.locator("section#logs")).not.toBeVisible();
  });
});

test.describe("Scénario: admin — gestion des utilisateurs", () => {
  test("admin_creerPuisSupprimerUtilisateur_fluxComplet", async ({
    page,
    isMobile,
  }) => {
    const emailTest = `e2e-test-${Date.now()}@example.com`;

    // Créer l'utilisateur
    await page.goto("/admin");
    if (isMobile) {
      await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
    } else {
      await page.locator('a[href="#utilisateurs"]').click();
    }
    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');

    // Vérifier le dialog de confirmation d'invitation
    const dialog = page.locator("dialog#dialog-invitation");
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await expect(dialog).toContainText(emailTest);
    await page.click("button#btn-fermer-invitation");

    // Retrouver la carte du compte créé
    const carteUser = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await expect(carteUser).toBeVisible({ timeout: 3000 });

    // Cliquer sur Supprimer → ouvre le dialog de confirmation
    await carteUser.locator("button.btn-danger").click();
    await expect(page.locator("dialog#dialog-supprimer-user")).toBeVisible({
      timeout: 3000,
    });

    // Confirmer la suppression
    await page.click("button#dialog-supprimer-user-confirmer");

    // Vérifier que l'utilisateur a disparu de la liste
    await expect(
      page.locator(".user-carte").filter({ hasText: emailTest }),
    ).not.toBeVisible({ timeout: 5000 });
  });
});

test.describe("Scénario: admin — modification utilisateur", () => {
  test("admin_modifierNom_sauvegarde", async ({ page, isMobile }) => {
    const emailTest = `e2e-nom-${Date.now()}@example.com`;

    await page.goto("/admin");
    if (isMobile) {
      await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
    } else {
      await page.locator('a[href="#utilisateurs"]').click();
    }
    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');
    const dialogInvit = page.locator("dialog#dialog-invitation");
    await expect(dialogInvit).toBeVisible({ timeout: 5000 });
    await page.click("button#btn-fermer-invitation");

    const carteUser = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await carteUser.locator("button", { hasText: "Modifier" }).click();
    await carteUser
      .locator('input[placeholder="Nom d\'affichage"]')
      .fill("Alice Test");
    await carteUser
      .locator("button.btn-primary", { hasText: "Enregistrer" })
      .click();

    await expect(
      page.locator(".user-carte").filter({ hasText: "Alice Test" }),
    ).toBeVisible({
      timeout: 5000,
    });

    // Nettoyage
    const carteNettoyage = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await carteNettoyage.locator("button.btn-danger").click();
    await page.click("button#dialog-supprimer-user-confirmer");
  });

  test("admin_changementRole_dialogConfirmation", async ({
    page,
    isMobile,
  }) => {
    const emailTest = `e2e-role-${Date.now()}@example.com`;

    await page.goto("/admin");
    if (isMobile) {
      await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
    } else {
      await page.locator('a[href="#utilisateurs"]').click();
    }
    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');
    const dialogInvit = page.locator("dialog#dialog-invitation");
    await expect(dialogInvit).toBeVisible({ timeout: 5000 });
    await page.click("button#btn-fermer-invitation");

    const carteUser = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await carteUser.locator("button", { hasText: "Modifier" }).click();
    await carteUser
      .locator('select[aria-label="Rôle de l\'utilisateur"]')
      .selectOption("admin");
    await carteUser
      .locator("button.btn-primary", { hasText: "Enregistrer" })
      .click();

    await expect(page.locator("dialog#dialog-confirmer-role")).toBeVisible({
      timeout: 3000,
    });
    await page.click("button#dialog-confirmer-role-annuler");
    await expect(
      page.locator("dialog#dialog-confirmer-role"),
    ).not.toBeVisible();

    // Nettoyage
    const carteNettoyage = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await carteNettoyage.locator("button.btn-danger").click();
    await page.click("button#dialog-supprimer-user-confirmer");
  });

  test("admin_revocationAdmin_badgeEtAnnulation", async ({
    page,
    isMobile,
  }) => {
    const emailTest = `e2e-revoc-${Date.now()}@example.com`;

    await page.goto("/admin");
    if (isMobile) {
      await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
    } else {
      await page.locator('a[href="#utilisateurs"]').click();
    }
    // Créer un utilisateur et le promouvoir admin
    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');
    await expect(page.locator("dialog#dialog-invitation")).toBeVisible({
      timeout: 5000,
    });
    await page.click("button#btn-fermer-invitation");

    const carteUser = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await carteUser.locator("button", { hasText: "Modifier" }).click();
    await carteUser
      .locator('select[aria-label="Rôle de l\'utilisateur"]')
      .selectOption("admin");
    await carteUser
      .locator("button.btn-primary", { hasText: "Enregistrer" })
      .click();
    await expect(page.locator("dialog#dialog-confirmer-role")).toBeVisible({
      timeout: 3000,
    });
    await page.click("button#dialog-confirmer-role-ok");
    await expect(
      page
        .locator(".user-carte")
        .filter({ hasText: emailTest })
        .locator(".badge-info"),
    ).toBeVisible({ timeout: 5000 });

    // Initier la révocation
    const carteAdmin = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await carteAdmin.locator("button", { hasText: "Modifier" }).click();
    await carteAdmin
      .locator('select[aria-label="Rôle de l\'utilisateur"]')
      .selectOption("user");
    await carteAdmin
      .locator("button.btn-primary", { hasText: "Enregistrer" })
      .click();
    await expect(page.locator("dialog#dialog-confirmer-role")).toBeVisible({
      timeout: 3000,
    });
    await page.click("button#dialog-confirmer-role-ok");

    // Vérifier le badge de révocation
    const carteRevoc = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await expect(
      carteRevoc.locator(".badge-warn", { hasText: "révocation" }),
    ).toBeVisible({
      timeout: 5000,
    });

    // Annuler la révocation
    await carteRevoc
      .locator("button", { hasText: "Annuler la révocation" })
      .click();
    await expect(
      carteRevoc.locator(".badge-warn", { hasText: "révocation" }),
    ).not.toBeVisible({
      timeout: 5000,
    });

    // Nettoyage
    const carteNettoyage = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await carteNettoyage.locator("button.btn-danger").click();
    await page.click("button#dialog-supprimer-user-confirmer");
  });
});

test.describe("Scénario: admin — API endpoints", () => {
  test("api_admin_jobs_retourne_200", async ({ request }) => {
    const rep = await request.get("/api/admin/jobs");
    expect(rep.status()).toBe(200);
  });

  test("api_admin_info_retourne_200", async ({ request }) => {
    const rep = await request.get("/api/admin/info");
    expect(rep.status()).toBe(200);
  });

  test("api_admin_non_accessible_sans_session", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const rep = await ctx.request.get("/api/admin/jobs");
    expect([401, 302, 403]).toContain(rep.status());
    await ctx.close();
  });

  test("api_admin_revoquerAdmin_seulAdmin_retourne409", async ({ request }) => {
    const repUsers = await request.get("/api/admin/utilisateurs");
    const users = await repUsers.json();
    const admins = users.filter((u: { role: string }) => u.role === "admin");
    if (admins.length !== 1) return;
    const rep = await request.post(
      `/api/admin/utilisateurs/${admins[0].id}/revoquer-admin`,
    );
    expect(rep.status()).toBe(400);
  });
});
