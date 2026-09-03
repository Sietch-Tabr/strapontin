import { test, expect, request as playwrightRequest } from "@playwright/test";
import { FICHIER_AUTH_ADMIN, FICHIER_AUTH_USER } from "./global-setup.js";
import {
  BASE_URL,
  creerContexteAdmin,
  supprimerUtilisateurParEmail,
  naviguerVersUtilisateurs,
} from "./helpers/admin.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

const emailsANettoyer: string[] = [];

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

  // #838 : avant correctif, une rétrogradation admin→user passait par un endpoint dédié
  // (/revoquer-admin) distinct du PATCH — toute modification nom/email saisie dans le même
  // formulaire était silencieusement perdue.
  test("admin_retrogradationCombineeAvecNom_conserveLeNom", async ({
    page,
    isMobile,
  }) => {
    const emailTest = `e2e-retro-nom-${Date.now()}@example.com`;
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
    // Attendre le badge admin (reflète rechargerUtilisateurs() côté serveur) avant de recliquer
    // « Modifier » — sinon la fermeture automatique du formulaire déclenchée par la sauvegarde
    // précédente (encore en vol) peut arriver après ce reclic et refermer l'édition qui vient de
    // rouvrir. Même pattern que le test sœur ci-dessus (ligne ~295).
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
      .locator('input[placeholder="Nom d\'affichage"]')
      .fill("Nom Conserve");
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

    await expect(
      page.locator(".user-carte").filter({ hasText: emailTest }),
    ).toContainText("Nom Conserve", { timeout: 5000 });
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
    await expect(page.locator(".source-carte")).toHaveCount(5);
    await expect(page.locator(".source-carte-large")).toHaveCount(1);
    await expect(page.locator(".source-carte-large")).toContainText(
      "Ticketmaster",
    );
    await expect(
      page.locator(".source-carte:not(.source-carte-large)"),
    ).toHaveCount(4);
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
      await expect(page.locator("p.etat-vide-texte")).toContainText(
        "Aucune salle ne correspond",
        { timeout: 3000 },
      );
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

  // Ce test effectue un appel HTTP réel vers web.digitick.com — une défaillance réseau
  // transitoire peut le faire échouer. retries: 1 tolère un incident passager sans masquer un vrai bug.
  test.describe("admin_sources_api_tester_slug_valide (réseau externe)", () => {
    test.describe.configure({ retries: 1 });

    test("admin_sources_api_tester_slug_valide", async () => {
      const ctx = await creerContexteAdmin();
      try {
        const uuid = "00000000-0000-0000-0000-000000000001";
        const rep = await ctx.post(
          `/api/admin/salles/${uuid}/tester-digitick`,
          {
            data: { slug: "lempreinte" },
          },
        );
        expect(rep.status()).toBe(200);
        const corps = await rep.json();
        expect(corps.accessible).toBe(true);
      } finally {
        await ctx.dispose();
      }
    });
  });

  test("admin_sources_api_tester_slug_invalide", async () => {
    const ctx = await creerContexteAdmin();
    try {
      const uuid = "00000000-0000-0000-0000-000000000001";
      const rep = await ctx.post(`/api/admin/salles/${uuid}/tester-digitick`, {
        data: { slug: "slug-inexistant-xyz-strapontin" },
      });
      expect(rep.status()).toBe(404);
      const corps = await rep.json();
      expect(corps.accessible).toBe(false);
    } finally {
      await ctx.dispose();
    }
  });

  test("admin_sources_api_modifier_slug_uuid_invalide_retourne_400", async () => {
    const ctx = await creerContexteAdmin();
    try {
      const rep = await ctx.put("/api/admin/salles/pas-un-uuid/digitick-slug", {
        data: { slug: "lempreinte" },
      });
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

  test("admin_sources_digitick_boutonAjouter_visible_dansVueDigitick", async ({
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
    // Comme admin_sources_toggle_digitick ci-dessus : attendre le toggle avant de cliquer,
    // sinon le clic peut atteindre le bouton avant la fin du chargement (EtatChargement)
    // qui conditionne son rendu (v-else), le rendant introuvable par intermittence.
    await expect(toggle).toBeVisible();
    await toggle.click();
    await expect(
      page.locator("button", { hasText: "Ajouter une salle" }),
    ).toBeVisible();
  });

  test("admin_sources_digitick_api_creerEtSupprimerSalle", async () => {
    const ctx = await creerContexteAdmin();
    let salleId: string | null = null;
    try {
      const repCreation = await ctx.post("/api/admin/digitick-salles", {
        data: {
          nom: "Salle Test E2E Digitick",
          slug: "salle-test-e2e",
          ville: "Paris",
          cp: "75001",
        },
      });
      expect(repCreation.status()).toBe(201);
      const corps = await repCreation.json();
      salleId = corps.salleId;
      expect(salleId).toBeTruthy();

      const repSalles = await ctx.get(
        "/api/admin/sources/salles?digitick=true&nom=Salle+Test+E2E+Digitick",
      );
      expect(repSalles.ok()).toBe(true);
      const donnees = await repSalles.json();
      const sallesDigitick: { nom: string }[] = donnees.salles ?? [];
      expect(
        sallesDigitick.some((s) => s.nom === "Salle Test E2E Digitick"),
      ).toBe(true);
    } finally {
      if (salleId) {
        await ctx.delete(`/api/admin/digitick-salles/${salleId}`);
      }
      await ctx.dispose();
    }
  });

  test("admin_sources_digitick_api_supprimer_salleAvecContrainte_retourne409", async () => {
    const ctx = await creerContexteAdmin();
    let salleId: string | null = null;
    try {
      const repCreation = await ctx.post("/api/admin/digitick-salles", {
        data: { nom: "Salle Test Contrainte", slug: "salle-contrainte-e2e" },
      });
      expect(repCreation.status()).toBe(201);
      salleId = (await repCreation.json()).salleId;

      const repSuivi = await ctx.post("/api/salles", {
        data: { nom: "Salle Test Contrainte", tmVenueId: null },
      });
      expect([200, 201, 409].includes(repSuivi.status())).toBe(true);

      const repSuppression = await ctx.delete(
        `/api/admin/digitick-salles/${salleId}`,
      );
      expect(repSuppression.status()).toBe(409);
    } finally {
      if (salleId) {
        await ctx.delete(`/api/salles/${salleId}`).catch(() => {});
        await ctx
          .delete(`/api/admin/digitick-salles/${salleId}`)
          .catch(() => {});
      }
      await ctx.dispose();
    }
  });

  test("admin_sources_digitick_api_rechercheSalleAvecSlug", async () => {
    const ctx = await creerContexteAdmin();
    let salleId: string | null = null;
    try {
      const repCreation = await ctx.post("/api/admin/digitick-salles", {
        data: { nom: "Zenith E2E Digitick", slug: "zenith-e2e" },
      });
      expect(repCreation.status()).toBe(201);
      salleId = (await repCreation.json()).salleId;

      const repRecherche = await ctx.get("/api/salles?q=Zenith+E2E");
      expect(repRecherche.ok()).toBe(true);
      const donnees = await repRecherche.json();
      const resultats: { salle: string; digitickSlug: string | null }[] =
        donnees.resultats ?? [];
      const trouvee = resultats.find((r) => r.salle === "Zenith E2E Digitick");
      expect(trouvee).toBeTruthy();
      expect(trouvee?.digitickSlug).toBe("zenith-e2e");
    } finally {
      if (salleId) {
        await ctx.delete(`/api/admin/digitick-salles/${salleId}`);
      }
      await ctx.dispose();
    }
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

  test("admin_jobs_desktop_boutonRelancerGlobal_absent_maintenance", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    const sectionMaintenance = page.locator("section#jobs .param-bloc", {
      hasText: "Maintenance",
    });
    await expect(sectionMaintenance).toBeVisible();
    // Le bouton global "Relancer les fetches maintenant" (#btn-relancer) reste réservé à la
    // section Fetches — la Maintenance a un bouton "Relancer" par job (v2.6.0, #706/#710).
    await expect(
      sectionMaintenance.locator("button#btn-relancer"),
    ).not.toBeAttached();
  });

  test("admin_jobs_desktop_boutonRelancerParJob_present_maintenance", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    const sectionMaintenance = page.locator("section#jobs .param-bloc", {
      hasText: "Maintenance",
    });
    await expect(sectionMaintenance).toBeVisible();
    const premiereCarte = sectionMaintenance.locator(".job-carte").first();
    await expect(
      premiereCarte.getByRole("button", { name: "Relancer" }),
    ).toBeVisible();
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
    expect(rep.status()).toBe(401);
    await ctx.close();
  });

  // #941 : aucun test ne vérifiait qu'un utilisateur authentifié mais non-admin (rôle
  // insuffisant, distinct de l'absence totale de session) reçoit bien 403 sur /api/admin/**
  // (SecurityConfiguration.java, .requestMatchers("/api/admin/**").hasRole("ADMIN")).
  test("api_admin_non_accessible_avec_role_utilisateur", async () => {
    const ctx = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: FICHIER_AUTH_USER,
    });
    try {
      const rep = await ctx.get("/api/admin/jobs");
      expect(rep.status()).toBe(403);
    } finally {
      await ctx.dispose();
    }
  });

  test("api_admin_sources_retourne_200", async ({ request }) => {
    const rep = await request.get("/api/admin/sources");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(Array.isArray(corps.sources)).toBe(true);
    expect(Array.isArray(corps.salles)).toBe(true);
    expect(corps.sources.length).toBe(5);
    const ids = corps.sources.map((s: { id: string }) => s.id);
    expect(ids).toContain("ticketmaster");
    expect(ids).toContain("eventim");
    expect(ids).toContain("deezer");
    expect(ids).toContain("digitick");
    expect(ids).toContain("wikidata");

    // #777 : TM/Eventim/Deezer doivent désormais exposer un décompte d'items suivis, comme
    // Digitick/Wikidata le faisaient déjà — sans ça les cartes admin ne peuvent pas afficher
    // le nombre de salles/artistes suivis via cette source.
    type Source = {
      id: string;
      nbSallesConfigurees: number | null;
      nbArtistesConfigures: number | null;
    };
    const sources: Source[] = corps.sources;
    const tm = sources.find((s) => s.id === "ticketmaster")!;
    const eventim = sources.find((s) => s.id === "eventim")!;
    const deezer = sources.find((s) => s.id === "deezer")!;
    expect(tm.nbSallesConfigurees).not.toBeUndefined();
    expect(tm.nbArtistesConfigures).not.toBeUndefined();
    expect(eventim.nbSallesConfigurees).not.toBeUndefined();
    expect(eventim.nbArtistesConfigures).not.toBeUndefined();
    expect(deezer.nbArtistesConfigures).not.toBeUndefined();
  });

  test("api_admin_revoquerAdmin_seulAdmin_retourne409", async ({ request }) => {
    const repUsers = await request.get("/api/admin/utilisateurs");
    const { utilisateurs: users } = await repUsers.json();
    const admins = users.filter((u: { role: string }) => u.role === "admin");
    if (admins.length !== 1) return;
    const rep = await request.post(
      `/api/admin/utilisateurs/${admins[0].id}/revoquer-admin`,
    );
    expect(rep.status()).toBe(400);
  });

  // #808 : annulerRevocationAdmin était le seul endpoint de gestion utilisateur sans garde
  // userId === adminId — un admin en cours de révocation pouvait annuler sa propre révocation.
  test("api_admin_annulerRevocationAdmin_soiMeme_retourne400", async ({
    request,
  }) => {
    const repProfil = await request.get("/api/user/profile");
    const profil = await repProfil.json();
    const rep = await request.delete(
      `/api/admin/utilisateurs/${profil.id}/revoquer-admin`,
    );
    expect(rep.status()).toBe(400);
  });

  // Bouton "Relancer" par job de maintenance (v2.6.0, #706/#710).
  test("api_admin_jobsRelancer_typeJobConnu_retourne200", async ({
    request,
  }) => {
    const rep = await request.post(
      "/api/admin/jobs/relancer?typeJob=" +
        encodeURIComponent("purger:logs-admin"),
    );
    expect(rep.status()).toBe(200);
  });

  // #837 : le worker retourne 400 (erreur cliente) pour un typeJob inconnu, jamais 503 — avant
  // correctif, WorkerProxyService masquait tous les échecs (400, 5xx, injoignable) sous un 503
  // générique, ce qui égarait le diagnostic d'un admin.
  test("api_admin_jobsRelancer_typeJobInconnu_retourne400", async ({
    request,
  }) => {
    const rep = await request.post(
      "/api/admin/jobs/relancer?typeJob=" +
        encodeURIComponent("job:inexistant"),
    );
    expect(rep.status()).toBe(400);
  });
});
