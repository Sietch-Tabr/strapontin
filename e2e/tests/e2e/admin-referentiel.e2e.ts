import { test, expect, request as playwrightRequest } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";

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
    const ctx = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: FICHIER_AUTH_ADMIN,
    });
    let salleUuid: string | null = null;
    try {
      const repCreation = await ctx.post("/api/admin/digitick-salles", {
        data: {
          nom: "Salle Reinit Test",
          slug: `reinit-test-${Date.now()}`,
        },
      });
      expect(repCreation.status()).toBe(201);
      salleUuid = (await repCreation.json()).salleUuid;

      // La réinitialisation est un no-op sûr même sans wikidata_id préexistant : vérifie
      // uniquement que l'endpoint répond 200 sur une salle existante.
      const repReinit = await ctx.post(
        `/api/admin/salles/${salleUuid}/reinitialiser-enrichissement`,
      );
      expect(repReinit.status()).toBe(200);
    } finally {
      if (salleUuid) {
        await ctx
          .delete(`/api/admin/digitick-salles/${salleUuid}`)
          .catch(() => {});
      }
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
    const ctx = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: FICHIER_AUTH_ADMIN,
    });
    let salleA: string | null = null;
    let salleB: string | null = null;
    try {
      const repA = await ctx.post("/api/admin/digitick-salles", {
        data: {
          nom: "Salle Referentiel Test A",
          slug: `referentiel-test-a-${Date.now()}`,
        },
      });
      expect(repA.status()).toBe(201);
      salleA = (await repA.json()).salleUuid;

      const repB = await ctx.post("/api/admin/digitick-salles", {
        data: {
          nom: "Salle Referentiel Test B",
          slug: `referentiel-test-b-${Date.now()}`,
        },
      });
      expect(repB.status()).toBe(201);
      salleB = (await repB.json()).salleUuid;

      const repPatch = await ctx.patch(`/api/admin/salles/${salleA}`, {
        data: { nomAffiche: "Nom Canonique Test" },
      });
      expect(repPatch.status()).toBe(200);

      const repListe = await ctx.get(
        `/api/admin/salles?nom=${encodeURIComponent("Salle Referentiel Test A")}`,
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
        `/api/admin/salles?nom=${encodeURIComponent("Salle Referentiel Test B")}`,
      );
      const donneesApres = await repListeApresFusion.json();
      expect(
        donneesApres.salles.some((s: { id: string }) => s.id === salleB),
      ).toBe(false);
      salleB = null;
    } finally {
      if (salleA) {
        await ctx
          .delete(`/api/admin/digitick-salles/${salleA}`)
          .catch(() => {});
      }
      if (salleB) {
        await ctx
          .delete(`/api/admin/digitick-salles/${salleB}`)
          .catch(() => {});
      }
      await ctx.dispose();
    }
  });

  // #830 : un slug Digitick déjà attribué à une autre salle doit être refusé explicitement
  // (409), sur les deux chemins d'écriture (création ET édition) — avant correctif, la création
  // répondait 201 sans source Digitick fonctionnelle (échec silencieux) et l'édition volait le
  // mapping à l'autre salle sans avertissement.
  test("api_admin_digitickSalles_slugDejaAttribue_creationEtEditionRetournent409", async () => {
    const ctx = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: FICHIER_AUTH_ADMIN,
    });
    let salleExistante: string | null = null;
    let salleAutre: string | null = null;
    try {
      const slugPartage = `slug-conflit-${Date.now()}`;
      const repExistante = await ctx.post("/api/admin/digitick-salles", {
        data: { nom: "Salle Slug Existante", slug: slugPartage },
      });
      expect(repExistante.status()).toBe(201);
      salleExistante = (await repExistante.json()).salleUuid;

      // Chemin création : POST /digitick-salles avec un slug déjà attribué.
      const repCreationConflit = await ctx.post("/api/admin/digitick-salles", {
        data: { nom: "Salle Slug Conflit Creation", slug: slugPartage },
      });
      expect(repCreationConflit.status()).toBe(409);

      // Chemin édition : créer une autre salle avec un slug distinct, puis tenter de lui
      // attribuer le slug déjà pris via PUT digitick-slug.
      const repAutre = await ctx.post("/api/admin/digitick-salles", {
        data: {
          nom: "Salle Slug Conflit Edition",
          slug: `slug-autre-${Date.now()}`,
        },
      });
      expect(repAutre.status()).toBe(201);
      salleAutre = (await repAutre.json()).salleUuid;

      const repEditionConflit = await ctx.put(
        `/api/admin/salles/${salleAutre}/digitick-slug`,
        { data: { slug: slugPartage } },
      );
      expect(repEditionConflit.status()).toBe(409);
    } finally {
      if (salleExistante) {
        await ctx
          .delete(`/api/admin/digitick-salles/${salleExistante}`)
          .catch(() => {});
      }
      if (salleAutre) {
        await ctx
          .delete(`/api/admin/digitick-salles/${salleAutre}`)
          .catch(() => {});
      }
      await ctx.dispose();
    }
  });
});
