import { test, expect, request as playwrightRequest } from "@playwright/test";
import { FICHIER_AUTH_ADMIN, FICHIER_AUTH_USER } from "./global-setup.js";
import {
  BASE_URL,
  creerContexteAdmin,
  creerSalleReferentiel,
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
    await page.locator('a[href="#communication"]').click();
    await expect(page.locator("section#communication")).toBeVisible();
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
    await page.selectOption("select.param-onglets-mobile", "#communication");
    await expect(page.locator("section#communication")).toBeVisible();
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

    // .bouton-danger n'est plus unique sur la carte depuis l'ajout du bouton
    // "Verrouiller" (#1228) — cibler par texte pour rester non ambigu.
    await carteUser.getByRole("button", { name: "Supprimer" }).click();
    // Nom accessible annoncé par le lecteur d'écran : l'objet de la confirmation.
    await expect(
      page.getByRole("dialog", { name: "Supprimer ce compte ?" }),
    ).toBeVisible({ timeout: 3000 });
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
      .locator("button.bouton-primaire", { hasText: "Enregistrer" })
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
      .locator("button.bouton-primaire", { hasText: "Enregistrer" })
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
      .locator("button.bouton-primaire", { hasText: "Enregistrer" })
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
      .locator("button.bouton-primaire", { hasText: "Enregistrer" })
      .click();
    await expect(page.locator("dialog#dialog-confirmer-role")).toBeVisible({
      timeout: 3000,
    });
    await page.click("button#dialog-confirmer-role-ok");

    const carteRevoc = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await expect(
      carteRevoc.locator(".badge-avertissement", { hasText: "révocation" }),
    ).toBeVisible({ timeout: 5000 });

    await carteRevoc
      .locator("button", { hasText: "Annuler la révocation" })
      .click();
    await expect(
      carteRevoc.locator(".badge-avertissement", { hasText: "révocation" }),
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
      .locator("button.bouton-primaire", { hasText: "Enregistrer" })
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
      .locator("button.bouton-primaire", { hasText: "Enregistrer" })
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
      await expect(page.locator("p.etat-vide-texte")).toContainText(
        "Aucune salle ne correspond",
        { timeout: 3000 },
      );
      await page.locator("input[type='search']").clear();
    } else {
      await expect(videTexte).toBeVisible();
    }
  });

  test("admin_sources_toggle_lieuEventim", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#sources"]').click();
    await expect(page.locator("section#sources")).toBeVisible();
    const toggle = page.locator(".source-vue-bascule button", {
      hasText: "Avec lieu Eventim",
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
      page.locator(".source-vue-bascule button", { hasText: "Sans source" }),
    ).toBeVisible();
  });

  // Ce test effectue un appel HTTP réel vers l'API Eventim — une défaillance réseau
  // transitoire peut le faire échouer. retries: 1 tolère un incident passager sans masquer un vrai bug.
  test.describe("admin_sources_api_tester_lieuEventim_connu (réseau externe)", () => {
    test.describe.configure({ retries: 1 });

    test("admin_sources_api_tester_lieuEventim_connu", async () => {
      const ctx = await creerContexteAdmin();
      try {
        const uuid = "00000000-0000-0000-0000-000000000001";
        const rep = await ctx.post(
          `/api/admin/salles/${uuid}/tester-lieu-eventim`,
          { data: { libelle: "Zénith Paris - La Villette" } },
        );
        expect(rep.status()).toBe(200);
        const corps = await rep.json();
        expect(corps.accessible).toBe(true);
        expect(corps.nbConcerts).toBeGreaterThan(0);
      } finally {
        await ctx.dispose();
      }
    });
  });

  test("admin_sources_api_tester_lieuEventim_vide_retourne404", async () => {
    const ctx = await creerContexteAdmin();
    try {
      const uuid = "00000000-0000-0000-0000-000000000001";
      const rep = await ctx.post(
        `/api/admin/salles/${uuid}/tester-lieu-eventim`,
        { data: { libelle: "  " } },
      );
      expect(rep.status()).toBe(404);
      expect((await rep.json()).accessible).toBe(false);
    } finally {
      await ctx.dispose();
    }
  });

  test("admin_sources_api_modifier_lieuEventim_uuid_invalide_retourne_400", async () => {
    const ctx = await creerContexteAdmin();
    try {
      const rep = await ctx.put("/api/admin/salles/pas-un-uuid/lieu-eventim", {
        data: { libelle: "Accor Arena" },
      });
      expect(rep.status()).toBe(400);
    } finally {
      await ctx.dispose();
    }
  });

  test("admin_sources_section_salles_lieuEventim_visible", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#sources"]').click();
    await expect(page.locator("section#sources")).toBeVisible();
    const toggle = page.locator(".source-vue-bascule button", {
      hasText: "Avec lieu Eventim",
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
      page.locator(".source-vue-bascule button", { hasText: "Sans source" }),
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
    const btnSansSource = page.locator(".source-vue-bascule button", {
      hasText: "Sans source",
    });
    await btnSansSource.click();
    await expect(
      page.locator("table#table-sources-salles, p.etat-vide-texte"),
    ).toBeVisible({ timeout: 5000 });
  });

  // Un lieu enregistré à la main fait passer la salle dans la vue « Avec lieu Eventim », et
  // un lieu déjà rattaché à une autre salle est refusé (jamais un vol silencieux).
  test("admin_sources_lieuEventim_api_enregistrerPuisConflit", async () => {
    const ctx = await creerContexteAdmin();
    try {
      const suffixe = Date.now();
      const salleA = await creerSalleReferentiel(
        ctx,
        `Ornithorynque${suffixe}`,
      );
      const salleB = await creerSalleReferentiel(ctx, `Kangourou${suffixe}`);
      const libelle = `LIEU E2E ${suffixe}`;

      const repA = await ctx.put(`/api/admin/salles/${salleA}/lieu-eventim`, {
        data: { libelle },
      });
      expect(repA.status()).toBe(200);
      const repListe = await ctx.get(
        `/api/admin/sources/salles?eventim=true&nom=Ornithorynque${suffixe}`,
      );
      const salles: { salleId: string; lieuEventim: string }[] = (
        await repListe.json()
      ).salles;
      expect(salles.find((s) => s.salleId === salleA)?.lieuEventim).toBe(
        libelle,
      );

      const repB = await ctx.put(`/api/admin/salles/${salleB}/lieu-eventim`, {
        data: { libelle },
      });
      expect(repB.status()).toBe(409);

      const repRetrait = await ctx.put(
        `/api/admin/salles/${salleA}/lieu-eventim`,
        { data: { libelle: "" } },
      );
      expect(repRetrait.status()).toBe(200);
    } finally {
      await ctx.dispose();
    }
  });
});

test.describe("Scénario: admin — onglet Jobs (cartes par thème)", () => {
  test("admin_jobs_themes_recuperationEtPurges_visibles", async ({ page }) => {
    await page.goto("/admin");
    await expect(
      page.locator("section#jobs .param-bloc", {
        hasText: "Récupération des concerts",
      }),
    ).toBeVisible();
    await expect(
      page.locator("section#jobs .param-bloc", { hasText: "Purges" }),
    ).toBeVisible();
  });

  // Chaque job s'affiche sous son nom : aucun identifiant technique (ex. gerer:comptes-inactifs).
  test("admin_jobs_aucunIdentifiantTechniqueAffiche", async ({ page }) => {
    await page.goto("/admin");
    await expect(page.locator("section#jobs .job-carte").first()).toBeVisible();
    const libelles = await page
      .locator("section#jobs .job-carte-libelle")
      .allInnerTexts();
    expect(libelles.length).toBeGreaterThan(0);
    for (const libelle of libelles) {
      expect(libelle).not.toMatch(/^[a-z]+:[a-z:-]+$/);
    }
    await expect(
      page.locator("section#jobs .param-bloc", { hasText: "À classer" }),
    ).not.toBeAttached();
  });

  test("admin_jobs_boutonRelancerGlobal_dansRecuperationSeulement", async ({
    page,
  }) => {
    await page.goto("/admin");
    const recuperation = page.locator("section#jobs .param-bloc", {
      hasText: "Récupération des concerts",
    });
    await expect(recuperation.locator("button#btn-relancer")).toBeVisible();
    await expect(page.locator("section#jobs button#btn-relancer")).toHaveCount(
      1,
    );
  });

  test("admin_jobs_boutonRelancerParJob_presentDansPurges", async ({
    page,
  }) => {
    await page.goto("/admin");
    const purges = page.locator("section#jobs .param-bloc", {
      hasText: "Purges",
    });
    await expect(
      purges
        .locator(".job-carte")
        .first()
        .getByRole("button", { name: "Relancer" }),
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
    expect(corps.sources.length).toBe(4);
    const ids = corps.sources.map((s: { id: string }) => s.id);
    expect(ids).toContain("ticketmaster");
    expect(ids).toContain("eventim");
    expect(ids).toContain("deezer");
    expect(ids).not.toContain("digitick");
    expect(ids).toContain("wikidata");

    // #777 : TM/Eventim/Deezer doivent désormais exposer un décompte d'items suivis, comme
    // Wikidata le faisait déjà — sans ça les cartes admin ne peuvent pas afficher
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

test.describe("Scénario: admin — onglet Communication", () => {
  // Le modèle pré-rempli bloque la publication tant qu'un champ entre crochets reste à
  // compléter ; le test ne publie jamais (emails et push réels).
  test("admin_incident_modelePreRempli_publicationBloqueeTantQueIncomplet", async ({
    page,
  }) => {
    await page.goto("/admin#communication");
    await expect(page.locator("#incident-titre")).toHaveValue(
      "Incident de sécurité sur Strapontin",
    );
    await expect(page.locator("#incident-message")).toHaveValue(
      /\[nature de l'incident\]/,
    );
    const publier = page.getByRole("button", {
      name: "Publier la communication",
    });
    await expect(publier).toBeDisabled();

    await page
      .locator("#incident-message")
      .fill(
        "Le 2 octobre, un accès non autorisé a été détecté. Contact : admin.",
      );

    await expect(publier).toBeEnabled();
  });
});
