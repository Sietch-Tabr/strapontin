import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";
import { creerContexteAdmin, creerSalleReferentiel } from "./helpers/admin.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

test.describe("Scénario: admin — onglets référentiel Salles/Artistes", () => {
  test("admin_desktop_section_salles_visible", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#salles"]').click();
    await expect(page.locator("section#salles")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_mobile_select_navigation_salles", async ({ page, isMobile }) => {
    if (!isMobile) test.skip();
    await page.goto("/admin");
    await page.selectOption("select.param-onglets-mobile", "#salles");
    await expect(page.locator("section#salles")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_desktop_section_artistes_visible", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#artistes"]').click();
    await expect(page.locator("section#artistes")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_mobile_select_navigation_artistes", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/admin");
    await page.selectOption("select.param-onglets-mobile", "#artistes");
    await expect(page.locator("section#artistes")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_salles_table_ou_etatVide_visible", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#salles"]').click();
    await expect(page.locator("section#salles")).toBeVisible();
    await expect(
      page.locator("section#salles table, section#salles p.etat-vide-texte"),
    ).toBeVisible();
  });

  test("admin_artistes_table_ou_etatVide_visible", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#artistes"]').click();
    await expect(page.locator("section#artistes")).toBeVisible();
    await expect(
      page.locator(
        "section#artistes table, section#artistes p.etat-vide-texte",
      ),
    ).toBeVisible();
  });

  test("admin_salles_lienWikidata_siPresent_pointeVersWikidataOrg", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#salles"]').click();
    const premierLien = page.locator("section#salles table tbody a").first();
    if (await premierLien.count()) {
      await expect(premierLien).toHaveAttribute(
        "href",
        /^https:\/\/www\.wikidata\.org\/wiki\//,
      );
    }
  });

  // Filtre ergonomique ajouté pour faciliter le repérage des candidats à corriger manuellement
  // dans une pagination volumineuse (v2.6.0, #710).
  test("admin_salles_filtreSansWikidata_visible_et_coche", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#salles"]').click();
    const checkbox = page.locator(
      "section#salles .source-filtre-checkbox input",
    );
    await expect(checkbox).toBeVisible();
    await checkbox.check();
    await expect(checkbox).toBeChecked();
  });

  test("admin_artistes_filtreSansWikidata_visible_et_coche", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#artistes"]').click();
    const checkbox = page.locator(
      "section#artistes .source-filtre-checkbox input",
    );
    await expect(checkbox).toBeVisible();
    await checkbox.check();
    await expect(checkbox).toBeChecked();
  });

  test("admin_salles_boutonReinitialiser_visible_siWikidataPresent", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#salles"]').click();
    const premierLien = page.locator("section#salles table tbody a").first();
    if (await premierLien.count()) {
      const ligne = premierLien.locator("xpath=ancestor::tr");
      await expect(
        ligne.getByRole("button", { name: "Réinitialiser" }),
      ).toBeVisible();
    }
  });

  // #829 : la sélection de fusion (UUID) ne référence que la page courante — un changement de
  // filtre sans réinitialisation laissait ouvrirFusion() chercher un UUID absent de la nouvelle
  // page chargée, ouvrant un dialog de fusion vide sans message explicite.
  test("admin_salles_changementFiltre_reinitialiseLaSelection", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#salles"]').click();
    const premiereCheckbox = page
      .locator("section#salles table tbody tr")
      .first()
      .locator('input[type="checkbox"]');
    if (!(await premiereCheckbox.count())) test.skip();
    await premiereCheckbox.check();
    await expect(premiereCheckbox).toBeChecked();

    await page
      .locator('section#salles input[aria-label="Rechercher une salle"]')
      .fill("zzz-inexistant-zzz");

    await expect(
      page
        .locator(".alerte-avertissement")
        .filter({ hasText: "Sélection réinitialisée" }),
    ).toBeVisible({ timeout: 5000 });
  });

  // #879 : tous les tests de fusion existants appellent l'API directement (POST
  // .../fusionner/...), sans jamais piloter les cases à cocher et le dialog de confirmation —
  // le bug de sélection cross-page (#829) ne pouvait donc jamais être détecté par la suite.
  test("admin_salles_fusionPiloteeParIhm_checkboxesEtDialogReels", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();

    const suffixe = Date.now();
    const nomA = `Ornithorynque${suffixe}`;
    const nomB = `Kangourou${suffixe}`;
    const ctx = await creerContexteAdmin();
    try {
      await creerSalleReferentiel(ctx, nomA);
      await creerSalleReferentiel(ctx, nomB);

      await page.goto("/admin");
      await page.locator('a[href="#salles"]').click();
      // Recherche par le suffixe partagé (timestamp) — présent dans les deux noms de test.
      // La saisie déclenche un fetch asynchrone (rechargerPage0) vers /api/admin/salles —
      // attendre cette réponse avant de vérifier les lignes filtrées, plutôt qu'un simple
      // timeout d'assertion, évite une course sur les navigateurs plus lents à réagir à
      // l'événement input (constaté sur webkit : recherche saisie mais table pas encore
      // rafraîchie au moment de l'assertion).
      const [reponseFiltre] = await Promise.all([
        page.waitForResponse(
          (resp) =>
            resp.url().includes("/api/admin/salles") &&
            resp.url().includes(String(suffixe)),
        ),
        page
          .locator('section#salles input[aria-label="Rechercher une salle"]')
          .fill(String(suffixe)),
      ]);
      expect(reponseFiltre.ok()).toBe(true);

      const ligneA = page.locator("section#salles table tbody tr", {
        hasText: nomA,
      });
      const ligneB = page.locator("section#salles table tbody tr", {
        hasText: nomB,
      });
      await expect(ligneA).toBeVisible({ timeout: 5000 });
      await expect(ligneB).toBeVisible({ timeout: 5000 });

      await ligneA.locator('input[type="checkbox"]').check();
      await ligneB.locator('input[type="checkbox"]').check();

      const boutonFusion = page.getByRole("button", {
        name: /Fusionner \(2\/2\)/,
      });
      await expect(boutonFusion).toBeEnabled();
      await boutonFusion.click();

      const dialog = page.locator(
        "dialog[aria-labelledby='titre-fusion-salle']",
      );
      await expect(dialog).toBeVisible({ timeout: 3000 });
      await dialog.getByRole("radio", { name: new RegExp(nomA) }).check();
      await dialog.getByRole("button", { name: "Fusionner" }).click();

      await expect(
        page.locator(".alerte-succes").filter({ hasText: "Salles fusionnées" }),
      ).toBeVisible({ timeout: 5000 });
      await expect(ligneB).not.toBeVisible({ timeout: 5000 });
      await expect(ligneA).toBeVisible();
    } finally {
      await ctx.dispose();
    }
  });
});

test.describe("Scénario: admin — API référentiel salles", () => {
  test("api_admin_salles_retourne_200", async ({ request }) => {
    const rep = await request.get("/api/admin/salles");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(Array.isArray(corps.salles)).toBe(true);
    expect(typeof corps.totalSalles).toBe("number");
  });

  test("api_admin_artistes_retourne_200", async ({ request }) => {
    const rep = await request.get("/api/admin/artistes");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(Array.isArray(corps.artistes)).toBe(true);
  });

  test("api_admin_salles_wikidataManquant_neRetourneQueSansWikidataId", async ({
    request,
  }) => {
    const rep = await request.get("/api/admin/salles?wikidataManquant=true");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(
      corps.salles.every(
        (s: { wikidataId: string | null }) => s.wikidataId == null,
      ),
    ).toBe(true);
  });

  test("api_admin_artistes_wikidataManquant_neRetourneQueSansWikidataId", async ({
    request,
  }) => {
    const rep = await request.get("/api/admin/artistes?wikidataManquant=true");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(
      corps.artistes.every(
        (a: { wikidataId: string | null }) => a.wikidataId == null,
      ),
    ).toBe(true);
  });

  test("api_admin_salles_reinitialiserEnrichissement_uuidInvalide_retourne400", async ({
    request,
  }) => {
    const rep = await request.post(
      "/api/admin/salles/pas-un-uuid/reinitialiser-enrichissement",
    );
    expect(rep.status()).toBe(400);
  });

  test("api_admin_salles_reinitialiserEnrichissement_salleInconnue_retourne404", async ({
    request,
  }) => {
    const rep = await request.post(
      "/api/admin/salles/00000000-0000-0000-0000-000000000099/reinitialiser-enrichissement",
    );
    expect(rep.status()).toBe(404);
  });

  test("api_admin_artistes_reinitialiserEnrichissement_artisteInconnu_retourne404", async ({
    request,
  }) => {
    const rep = await request.post(
      "/api/admin/artistes/00000000-0000-0000-0000-000000000099/reinitialiser-enrichissement",
    );
    expect(rep.status()).toBe(404);
  });

  test("api_admin_salles_reinitialiserEnrichissement_fluxComplet_effaceWikidataId", async () => {
    const ctx = await creerContexteAdmin();
    try {
      const salleId = await creerSalleReferentiel(
        ctx,
        `Reinitialisation${Date.now()}`,
      );

      // La réinitialisation est un no-op sûr même sans wikidata_id préexistant : vérifie
      // uniquement que l'endpoint répond 200 sur une salle existante.
      const repReinit = await ctx.post(
        `/api/admin/salles/${salleId}/reinitialiser-enrichissement`,
      );
      expect(repReinit.status()).toBe(200);
    } finally {
      await ctx.dispose();
    }
  });

  test("api_admin_salles_modifierNomAffiche_uuidInvalide_retourne400", async ({
    request,
  }) => {
    const rep = await request.patch("/api/admin/salles/pas-un-uuid", {
      data: { nomAffiche: "Test" },
    });
    expect(rep.status()).toBe(400);
  });

  test("api_admin_salles_modifierNomAffiche_salleInconnue_retourne404", async ({
    request,
  }) => {
    const rep = await request.patch(
      "/api/admin/salles/00000000-0000-0000-0000-000000000099",
      { data: { nomAffiche: "Test" } },
    );
    expect(rep.status()).toBe(404);
  });

  test("api_admin_salles_fusionner_uuidIdentiques_retourne400", async ({
    request,
  }) => {
    const uuid = "00000000-0000-0000-0000-000000000001";
    const rep = await request.post(
      `/api/admin/salles/${uuid}/fusionner/${uuid}`,
    );
    expect(rep.status()).toBe(400);
  });

  test("api_admin_artistes_fusionner_uuidIdentiques_retourne400", async ({
    request,
  }) => {
    const uuid = "00000000-0000-0000-0000-000000000001";
    const rep = await request.post(
      `/api/admin/artistes/${uuid}/fusionner/${uuid}`,
    );
    expect(rep.status()).toBe(400);
  });

  test("api_admin_salles_modifierEtFusionner_fluxComplet", async () => {
    const ctx = await creerContexteAdmin();
    const suffixe = Date.now();
    const nomA = `Hippopotame${suffixe}`;
    const nomB = `Salamandre${suffixe}`;
    try {
      const salleA = await creerSalleReferentiel(ctx, nomA);
      const salleB = await creerSalleReferentiel(ctx, nomB);

      const repPatch = await ctx.patch(`/api/admin/salles/${salleA}`, {
        data: { nomAffiche: "Nom Canonique Test" },
      });
      expect(repPatch.status()).toBe(200);

      const repListe = await ctx.get(
        `/api/admin/salles?nom=${encodeURIComponent(nomA)}`,
      );
      const donnees = await repListe.json();
      const trouvee = donnees.salles.find(
        (s: { id: string }) => s.id === salleA,
      );
      expect(trouvee?.nomAffiche).toBe("Nom Canonique Test");

      const repFusion = await ctx.post(
        `/api/admin/salles/${salleB}/fusionner/${salleA}`,
      );
      expect(repFusion.status()).toBe(200);

      const repListeApresFusion = await ctx.get(
        `/api/admin/salles?nom=${encodeURIComponent(nomB)}`,
      );
      const donneesApres = await repListeApresFusion.json();
      expect(
        donneesApres.salles.some((s: { id: string }) => s.id === salleB),
      ).toBe(false);
    } finally {
      await ctx.dispose();
    }
  });

  // #879 : aucun équivalent "flux complet" n'existait côté artistes (contrairement aux salles) —
  // édition du nom affiché puis fusion, en vérifiant la disparition de la source absorbée.
  //
  // Noms délibérément sans substring partagé (pas de préfixe/suffixe commun, ex. timestamp) :
  // POST /api/artistes passe par la dédup floue de suivi (CorrespondanceArtiste.estDoublonArtiste,
  // Jaro ≥ 0.95 | containment ≥ 0.65) — deux noms de test qui ne diffèrent que par 1 caractère
  // (ex. suffixe "-A"/"-B" sur un même préfixe long) sont détectés comme doublon du même artiste
  // et le second POST échoue en 409 — même contrainte que creerSalleReferentiel côté salles.
  test("api_admin_artistes_modifierEtFusionner_fluxComplet", async () => {
    const ctx = await creerContexteAdmin();
    const nomA = `Zzargonautfusiontest${Date.now()}`;
    const nomB = `Zzbaobabchinookwomble${Date.now()}`;
    let artisteA: string | null = null;
    let artisteB: string | null = null;
    try {
      const repAjoutA = await ctx.post("/api/artistes", {
        data: { nom: nomA },
      });
      expect(repAjoutA.status()).toBe(201);
      const repListeA = await ctx.get(
        `/api/admin/artistes?nom=${encodeURIComponent(nomA)}`,
      );
      artisteA = (await repListeA.json()).artistes[0]?.id ?? null;
      expect(artisteA).not.toBeNull();

      const repAjoutB = await ctx.post("/api/artistes", {
        data: { nom: nomB },
      });
      expect(repAjoutB.status()).toBe(201);
      const repListeB = await ctx.get(
        `/api/admin/artistes?nom=${encodeURIComponent(nomB)}`,
      );
      artisteB = (await repListeB.json()).artistes[0]?.id ?? null;
      expect(artisteB).not.toBeNull();

      const repPatch = await ctx.patch(`/api/admin/artistes/${artisteA}`, {
        data: { nomAffiche: "Nom Canonique Artiste Test" },
      });
      expect(repPatch.status()).toBe(200);

      const repFusion = await ctx.post(
        `/api/admin/artistes/${artisteB}/fusionner/${artisteA}`,
      );
      expect(repFusion.status()).toBe(200);

      const repListeApresFusion = await ctx.get(
        `/api/admin/artistes?nom=${encodeURIComponent(nomB)}`,
      );
      const donneesApres = await repListeApresFusion.json();
      expect(
        donneesApres.artistes.some((a: { id: string }) => a.id === artisteB),
      ).toBe(false);
      artisteB = null;

      const repListeFinale = await ctx.get(
        `/api/admin/artistes?nom=${encodeURIComponent(nomA)}`,
      );
      const finale = (await repListeFinale.json()).artistes.find(
        (a: { id: string }) => a.id === artisteA,
      );
      expect(finale?.nomAffiche).toBe("Nom Canonique Artiste Test");
    } finally {
      if (artisteA)
        await ctx.delete(`/api/artistes/${artisteA}`).catch(() => {});
      if (artisteB)
        await ctx.delete(`/api/artistes/${artisteB}`).catch(() => {});
      await ctx.dispose();
    }
  });

  // #879 : intégrité post-fusion avec chevauchement réel de données préexistant — l'utilisateur
  // admin suit les deux salles (user_salles en conflit potentiel) avant la fusion ; après fusion,
  // le suivi doit être préservé sur la cible sans doublon ni exception (contrainte unique
  // (user_id, salle_id)).
  test("api_admin_salles_fusion_chevauchementUserSalles_preserveLeSuiviSansDoublon", async () => {
    const ctx = await creerContexteAdmin();
    const suffixe = Date.now();
    const nomA = `Ornithorynque${suffixe}`;
    const nomB = `Kangourou${suffixe}`;
    let salleA: string | null = null;
    try {
      salleA = await creerSalleReferentiel(ctx, nomA);
      const salleB = await creerSalleReferentiel(ctx, nomB);

      // L'admin (utilisateur) suit les deux salles avant la fusion — chevauchement réel.
      const repSuiviA = await ctx.post("/api/salles", { data: { nom: nomA } });
      expect(repSuiviA.status()).toBe(201);
      const repSuiviB = await ctx.post("/api/salles", { data: { nom: nomB } });
      expect(repSuiviB.status()).toBe(201);

      const repMesSallesAvant = await ctx.get(
        `/api/salles?q=${encodeURIComponent(String(suffixe))}`,
      );
      const mesSallesAvant = (await repMesSallesAvant.json()).salles;
      expect(mesSallesAvant.length).toBe(2);

      const repFusion = await ctx.post(
        `/api/admin/salles/${salleB}/fusionner/${salleA}`,
      );
      expect(repFusion.status()).toBe(200);

      const repMesSallesApres = await ctx.get(
        `/api/salles?q=${encodeURIComponent(String(suffixe))}`,
      );
      const mesSallesApres = (await repMesSallesApres.json()).salles;
      expect(mesSallesApres.length).toBe(1);
      expect(mesSallesApres[0].id).toBe(salleA);
    } finally {
      if (salleA) {
        await ctx.delete(`/api/salles/${salleA}`).catch(() => {});
      }
      await ctx.dispose();
    }
  });
});
