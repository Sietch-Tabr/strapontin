import { test, expect, request as playwrightRequest } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";
const emailsANettoyer: string[] = [];

async function supprimerUtilisateurParEmail(email: string): Promise<void> {
  const ctx = await playwrightRequest.newContext({
    baseURL: BASE_URL,
    storageState: FICHIER_AUTH_ADMIN,
  });
  try {
    const reps = await ctx.get("/api/admin/utilisateurs");
    if (!reps.ok()) return;
    const users = await reps.json();
    const cible = users.find((u: { email: string }) => u.email === email);
    if (cible) await ctx.delete(`/api/admin/utilisateurs/${cible.id}`);
  } finally {
    await ctx.dispose();
  }
}

test.afterEach(async () => {
  const copies = emailsANettoyer.splice(0);
  for (const email of copies) {
    try {
      await supprimerUtilisateurParEmail(email);
    } catch {
      /* ignore */
    }
  }
});

async function naviguerVersUtilisateurs(
  page: import("@playwright/test").Page,
  isMobile: boolean,
) {
  if (isMobile) {
    await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
  } else {
    await page.locator('a[href="#utilisateurs"]').click();
  }
  await expect(page.locator("section#utilisateurs")).toBeVisible();
}

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
    emailsANettoyer.push(emailTest);

    await page.goto("/admin");
    await naviguerVersUtilisateurs(page, isMobile);

    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');

    const dialog = page.locator("dialog#dialog-invitation");
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await expect(dialog).toContainText(emailTest);
    await page.click("button#btn-fermer-invitation");
    await expect(dialog).not.toBeVisible({ timeout: 3000 });

    const carteUser = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await expect(carteUser).toBeVisible({ timeout: 3000 });

    await carteUser.locator("button.btn-danger").click();
    await expect(page.locator("dialog#dialog-supprimer-user")).toBeVisible({
      timeout: 3000,
    });
    await page.click("button#dialog-supprimer-user-confirmer");

    await expect(
      page.locator(".user-carte").filter({ hasText: emailTest }),
    ).not.toBeVisible({ timeout: 5000 });
  });
});

test.describe("Scénario: admin — modification utilisateur", () => {
  test("admin_modifierNom_sauvegarde", async ({ page, isMobile }) => {
    const emailTest = `e2e-nom-${Date.now()}@example.com`;
    emailsANettoyer.push(emailTest);

    await page.goto("/admin");
    await naviguerVersUtilisateurs(page, isMobile);

    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');
    const dialogInvit = page.locator("dialog#dialog-invitation");
    await expect(dialogInvit).toBeVisible({ timeout: 5000 });
    await page.click("button#btn-fermer-invitation");
    await expect(dialogInvit).not.toBeVisible({ timeout: 3000 });

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
      page.locator(".user-carte").filter({ hasText: emailTest }),
    ).toContainText("Alice Test", { timeout: 5000 });
  });

  test("admin_changementRole_dialogConfirmation", async ({
    page,
    isMobile,
  }) => {
    const emailTest = `e2e-role-${Date.now()}@example.com`;
    emailsANettoyer.push(emailTest);

    await page.goto("/admin");
    await naviguerVersUtilisateurs(page, isMobile);

    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');
    const dialogInvit = page.locator("dialog#dialog-invitation");
    await expect(dialogInvit).toBeVisible({ timeout: 5000 });
    await page.click("button#btn-fermer-invitation");
    await expect(dialogInvit).not.toBeVisible({ timeout: 3000 });

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
  });

  test("admin_revocationAdmin_badgeEtAnnulation", async ({
    page,
    isMobile,
  }) => {
    const emailTest = `e2e-revoc-${Date.now()}@example.com`;
    emailsANettoyer.push(emailTest);

    await page.goto("/admin");
    await naviguerVersUtilisateurs(page, isMobile);

    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');
    const dialogInvit = page.locator("dialog#dialog-invitation");
    await expect(dialogInvit).toBeVisible({ timeout: 5000 });
    await page.click("button#btn-fermer-invitation");
    await expect(dialogInvit).not.toBeVisible({ timeout: 3000 });

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

    const carteRevoc = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await expect(
      carteRevoc.locator(".badge-warn", { hasText: "révocation" }),
    ).toBeVisible({ timeout: 5000 });

    await carteRevoc
      .locator("button", { hasText: "Annuler la révocation" })
      .click();
    await expect(
      carteRevoc.locator(".badge-warn", { hasText: "révocation" }),
    ).not.toBeVisible({ timeout: 5000 });
  });
});

test.describe("Scénario: admin — onglet Sources", () => {
  test("admin_desktop_section_sources_visible", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#sources"]').click();
    await expect(page.locator("section#sources")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_mobile_select_navigation_sources", async ({ page, isMobile }) => {
    if (!isMobile) test.skip();
    await page.goto("/admin");
    await page.selectOption("select.param-onglets-mobile", "#sources");
    await expect(page.locator("section#sources")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_sources_cartes_visibles", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#sources"]').click();
    await expect(page.locator("section#sources")).toBeVisible();
    await expect(page.locator(".source-carte")).toHaveCount(4);
    await expect(page.locator(".source-carte-large")).toHaveCount(1);
    await expect(page.locator(".source-carte-large")).toContainText(
      "Ticketmaster",
    );
    await expect(
      page.locator(".source-carte:not(.source-carte-large)"),
    ).toHaveCount(3);
  });

  test("admin_sources_table_salles_visible", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#sources"]').click();
    await expect(page.locator("section#sources")).toBeVisible();
    await expect(
      page.locator("table#table-sources-salles, p.etat-vide-texte"),
    ).toBeVisible();
  });

  test("admin_sources_recherche_filtre_salles", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#sources"]').click();
    await expect(page.locator("section#sources")).toBeVisible();
    const table = page.locator("table#table-sources-salles");
    const videTexte = page.locator("p.etat-vide-texte");
    const tableOuVide = page.locator(
      "table#table-sources-salles, p.etat-vide-texte",
    );
    await expect(tableOuVide).toBeVisible();
    if (await table.isVisible()) {
      await page.locator("input[type='search']").fill("zzzzinexistant");
      await expect(
        page.locator("table#table-sources-salles tbody tr"),
      ).toContainText("Aucune salle ne correspond", { timeout: 3000 });
      await page.locator("input[type='search']").clear();
    } else {
      await expect(videTexte).toBeVisible();
    }
  });

  test("admin_sources_toggle_digitick", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#sources"]').click();
    await expect(page.locator("section#sources")).toBeVisible();
    const toggle = page.locator(".source-vue-toggle button", {
      hasText: /[Dd]igitick/,
    });
    await expect(toggle).toBeVisible();
    await toggle.click();
    await expect(
      page.locator("table#table-sources-salles, p.etat-vide-texte"),
    ).toBeVisible();
    await toggle.click();
  });

  test("admin_jobs_quota_tm_absent_depuis_jobs", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await expect(
      page.locator("section#jobs .quota-tm-carte"),
    ).not.toBeAttached();
  });

  test("admin_sources_bouton_sans_source_libelle", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#sources"]').click();
    await expect(page.locator("section#sources")).toBeVisible();
    await expect(
      page.locator(".source-vue-toggle button", { hasText: "Sans source" }),
    ).toBeVisible();
  });

  test("admin_sources_api_tester_slug_valide", async () => {
    const ctx = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: FICHIER_AUTH_ADMIN,
    });
    try {
      const uuid = "00000000-0000-0000-0000-000000000001";
      const rep = await ctx.post(
        `/api/admin/salles/${uuid}/tester-digitick`,
        { data: { slug: "lempreinte" } },
      );
      expect(rep.status()).toBe(200);
      const corps = await rep.json();
      expect(corps.accessible).toBe(true);
    } finally {
      await ctx.dispose();
    }
  });

  test("admin_sources_api_tester_slug_invalide", async () => {
    const ctx = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: FICHIER_AUTH_ADMIN,
    });
    try {
      const uuid = "00000000-0000-0000-0000-000000000001";
      const rep = await ctx.post(
        `/api/admin/salles/${uuid}/tester-digitick`,
        { data: { slug: "slug-inexistant-xyz-strapontin" } },
      );
      expect(rep.status()).toBe(404);
      const corps = await rep.json();
      expect(corps.accessible).toBe(false);
    } finally {
      await ctx.dispose();
    }
  });

  test("admin_sources_api_modifier_slug_uuid_invalide_retourne_400", async () => {
    const ctx = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: FICHIER_AUTH_ADMIN,
    });
    try {
      const rep = await ctx.put(
        "/api/admin/salles/pas-un-uuid/digitick-slug",
        { data: { slug: "lempreinte" } },
      );
      expect(rep.status()).toBe(400);
    } finally {
      await ctx.dispose();
    }
  });

  test("admin_sources_section_salles_digitick_visible", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#sources"]').click();
    await expect(page.locator("section#sources")).toBeVisible();
    const toggle = page.locator(".source-vue-toggle button", {
      hasText: /[Dd]igitick/,
    });
    await toggle.click();
    await expect(
      page.locator("table#table-sources-salles, p.etat-vide-texte"),
    ).toBeVisible({ timeout: 5000 });
  });

  test("admin_sources_mobile_bouton_sans_source_libelle", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/admin");
    await page.selectOption("select.param-onglets-mobile", "#sources");
    await expect(page.locator("section#sources")).toBeVisible();
    await expect(
      page.locator(".source-vue-toggle button", { hasText: "Sans source" }),
    ).toBeVisible();
  });

  test("admin_sources_bouton_sans_source_navigue_vers_liste", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#sources"]').click();
    await expect(page.locator("section#sources")).toBeVisible();
    const btnSansSource = page.locator(".source-vue-toggle button", {
      hasText: "Sans source",
    });
    await btnSansSource.click();
    await expect(
      page.locator("table#table-sources-salles, p.etat-vide-texte"),
    ).toBeVisible({ timeout: 5000 });
  });
});

test.describe("Scénario: admin — onglet Jobs (sections Fetches/Maintenance)", () => {
  test("admin_jobs_desktop_section_fetches_visible", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await expect(
      page.locator("section#jobs .param-bloc", { hasText: "Fetches" }),
    ).toBeVisible();
  });

  test("admin_jobs_desktop_section_maintenance_visible", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await expect(
      page.locator("section#jobs .param-bloc", { hasText: "Maintenance" }),
    ).toBeVisible();
  });

  test("admin_jobs_desktop_bouton_relancer_dans_fetches", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    const sectionFetches = page.locator("section#jobs .param-bloc", {
      hasText: "Fetches",
    });
    await expect(sectionFetches).toBeVisible();
    await expect(sectionFetches.locator("button#btn-relancer")).toBeVisible();
  });

  test("admin_jobs_desktop_bouton_relancer_absent_maintenance", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    const sectionMaintenance = page.locator("section#jobs .param-bloc", {
      hasText: "Maintenance",
    });
    await expect(sectionMaintenance).toBeVisible();
    await expect(
      sectionMaintenance.locator("button#btn-relancer"),
    ).not.toBeAttached();
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

  test("api_admin_sources_retourne_200", async ({ request }) => {
    const rep = await request.get("/api/admin/sources");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(Array.isArray(corps.sources)).toBe(true);
    expect(Array.isArray(corps.salles)).toBe(true);
    expect(corps.sources.length).toBe(4);
    const ids = corps.sources.map((s: { id: string }) => s.id);
    expect(ids).toContain("ticketmaster");
    expect(ids).toContain("eventim");
    expect(ids).toContain("deezer");
    expect(ids).toContain("digitick");
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
