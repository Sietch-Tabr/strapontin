import { test, expect, request as playwrightRequest } from "@playwright/test";
import { FICHIER_AUTH_ADMIN, FICHIER_AUTH_USER } from "./global-setup.js";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";
const artistesANettoyer: string[] = [];
const sallesANettoyer: string[] = [];

async function supprimerArtisteParNom(nom: string): Promise<void> {
  const ctx = await playwrightRequest.newContext({
    baseURL: BASE_URL,
    storageState: FICHIER_AUTH_USER,
  });
  try {
    const rep = await ctx.get("/api/artistes");
    if (!rep.ok()) return;
    const corps = await rep.json();
    const artistes: { id: string; artisteAffiche: string }[] =
      corps.artistes ?? [];
    const cible = artistes.find((a) =>
      a.artisteAffiche.toLowerCase().includes(nom.toLowerCase()),
    );
    if (cible) await ctx.delete(`/api/artistes/${cible.id}`);
  } finally {
    await ctx.dispose();
  }
}

async function supprimerSalleParNom(nom: string): Promise<void> {
  const ctx = await playwrightRequest.newContext({
    baseURL: BASE_URL,
    storageState: FICHIER_AUTH_USER,
  });
  try {
    const rep = await ctx.get("/api/salles");
    if (!rep.ok()) return;
    const corps = await rep.json();
    const salles: { id: string; nom: string }[] = corps.salles ?? [];
    const cible = salles.find((s) =>
      s.nom.toLowerCase().includes(nom.toLowerCase()),
    );
    if (cible) await ctx.delete(`/api/salles/${cible.id}`);
  } finally {
    await ctx.dispose();
  }
}

test.afterEach(async () => {
  const artistes = artistesANettoyer.splice(0);
  const salles = sallesANettoyer.splice(0);
  for (const nom of artistes) {
    try {
      await supprimerArtisteParNom(nom);
    } catch {
      /* ignore */
    }
  }
  for (const nom of salles) {
    try {
      await supprimerSalleParNom(nom);
    } catch {
      /* ignore */
    }
  }
});

test.describe("Scénario: catalogue — artistes", () => {
  test("artistes_ajouterEtSupprimer_fluxComplet_toastSuccesAuDeux", async ({
    page,
  }) => {
    // Nettoyage préventif : garantit un état propre même si un run précédent a échoué
    await supprimerArtisteParNom("Nina Simone");

    await page.goto("/artistes");

    // Rechercher et suivre
    await page.fill("input#q-artiste", "Nina Simone");
    await page.getByRole("button", { name: "Rechercher" }).click();

    // Correspondance exacte plutôt que .first() : les suggestions Wikidata/Deezer combinées
    // (#717) incluent désormais aussi des variantes non pertinentes (karaoké, tribute — cette
    // dernière filtrée par #768 et provoquant une erreur si sélectionnée) classées avant le nom
    // canonique selon la source.
    const btnSuivre = page.getByRole("button", {
      name: "Suivre Nina Simone",
      exact: true,
    });
    await expect(btnSuivre).toBeVisible({ timeout: 25000 });

    const ariaLabel = (await btnSuivre.getAttribute("aria-label")) ?? "";
    const nomArtiste = ariaLabel.replace(/^Suivre\s+/i, "").trim();

    artistesANettoyer.push(nomArtiste);
    await btnSuivre.click();
    // L'ajout produit un flash fetchEnCours (.alerte-info), pas .alerte-succes
    await expect(page.locator(".alerte-info").first()).toBeVisible({
      timeout: 5000,
    });

    // Suppression inline post-test
    const btnSupprimer = page.locator(
      `button[aria-label="Supprimer ${nomArtiste}"]`,
    );
    await expect(btnSupprimer).toBeVisible({ timeout: 5000 });
    await btnSupprimer.click();
    await page.locator("dialog#dialog-confirmer-suppr-artiste").waitFor();
    await page.click(
      "dialog#dialog-confirmer-suppr-artiste button.bouton-danger",
    );
    await expect(page.locator(".alerte-succes").first()).toBeVisible({
      timeout: 5000,
    });
    // Artiste supprimé inline — retirer de la liste de nettoyage afterEach
    artistesANettoyer.splice(0);
  });

  // #717 : suggestion Wikidata Search — badge + description affichés à l'autocomplete.
  // Restreint à chromium : dépend de la disponibilité réelle de l'API Wikidata (pas de contrôle
  // sur son rate-limit), éviter de le tester 5x en parallèle sur tous les navigateurs.
  test(
    "artistes_rechercheWikidata_afficheBadgeEtDescription",
    { tag: "@chromium-only" },
    async ({ page, browserName }) => {
      test.skip(
        browserName !== "chromium",
        "dépendance API Wikidata externe — chromium only",
      );

      await page.goto("/artistes");
      await page.fill("input#q-artiste", "Stromae");
      await page.getByRole("button", { name: "Rechercher" }).click();

      const badgeWikidata = page.locator(".badge-source-metadonnees", {
        hasText: "Wikidata",
      });
      await expect(badgeWikidata.first()).toBeVisible({ timeout: 25000 });
      // Chaque résultat dit d'où il vient : aucun n'est un nom nu.
      const resultats = page.locator("section.section-resultats .carte-item");
      const total = await resultats.count();
      for (let i = 0; i < total; i++) {
        await expect(
          resultats.nth(i).locator(".badge-source-metadonnees"),
        ).toBeVisible();
      }
    },
  );
});

test.describe("Scénario: catalogue — salles", () => {
  test("salles_ajouterEtSupprimer_fluxComplet_toastSuccesAuDeux", async ({
    page,
  }) => {
    await page.goto("/salles");

    // Rechercher et suivre
    await page.fill("input#q-salle", "Olympia");
    await page.getByRole("button", { name: "Rechercher" }).click();

    const btnSuivre = page.getByRole("button", { name: /Suivre/ }).first();
    await expect(btnSuivre).toBeVisible({ timeout: 25000 });

    const ariaLabel = (await btnSuivre.getAttribute("aria-label")) ?? "";
    const nomSalle = ariaLabel.replace(/^Suivre\s+/i, "").trim();

    sallesANettoyer.push(nomSalle);
    await btnSuivre.click();
    // Même message qu'à l'ajout d'un artiste : la recherche de ses concerts est lancée.
    await expect(
      page
        .locator(".alerte-info")
        .filter({ hasText: "Recherche des concerts" }),
    ).toBeVisible({ timeout: 5000 });

    // Suppression inline post-test
    const btnSupprimer = page.locator(
      `button[aria-label="Supprimer ${nomSalle}"]`,
    );
    await expect(btnSupprimer).toBeVisible({ timeout: 5000 });
    await btnSupprimer.click();
    await page.locator("dialog#dialog-confirmer-suppr-salle").waitFor();
    await page.click(
      "dialog#dialog-confirmer-suppr-salle button.bouton-danger",
    );
    await expect(page.locator(".alerte-succes").first()).toBeVisible({
      timeout: 5000,
    });
    // Salle supprimée inline — retirer de la liste de nettoyage afterEach
    sallesANettoyer.splice(0);
  });

  // #718 : suggestion Nominatim — badge "osm" affiché à l'autocomplete. Restreint à chromium :
  // dépend de la disponibilité réelle de l'API Nominatim (pas de contrôle sur son rate-limit).
  test(
    "salles_rechercheNominatim_afficheBadgeOsm",
    { tag: "@chromium-only" },
    async ({ page, browserName }) => {
      test.skip(
        browserName !== "chromium",
        "dépendance API Nominatim externe — chromium only",
      );

      await page.goto("/salles");
      await page.fill("input#q-salle", "Rockstore Montpellier");
      await page.getByRole("button", { name: "Rechercher" }).click();

      const badgeOsm = page.locator(".badge-source-metadonnees", {
        hasText: "osm",
      });
      await expect(badgeOsm.first()).toBeVisible({ timeout: 25000 });
    },
  );
});

test.describe("Scénario: catalogue — couverture des salles suivies", () => {
  // Une salle qu'aucune source ne connaît est signalée comme non couverte, pour que l'absence
  // de concert ne passe pas pour une absence de programmation.
  test("salles_api_chaqueSalleSuivieExposeLeDrapeauCouverte", async ({
    page,
  }) => {
    const rep = await page.request.get("/api/salles");
    expect(rep.ok()).toBe(true);
    const salles = (await rep.json()).salles as { couverte: unknown }[];
    for (const salle of salles) {
      expect(typeof salle.couverte).toBe("boolean");
    }
  });
});

test.describe("Scénario: catalogue — recherche salle caractères spéciaux", () => {
  test("api_recherche_salle_apostrophe_retourne_200_sans_erreur", async ({
    request,
  }) => {
    const rep = await request.get("/api/salles?q=l%27olympia");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(corps).toHaveProperty("resultats");
    expect(Array.isArray(corps.resultats)).toBe(true);
  });

  test("api_recherche_salle_accent_terme_retourne_200_sans_erreur", async ({
    request,
  }) => {
    const rep = await request.get("/api/salles?q=z%C3%A9nith");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(corps).toHaveProperty("resultats");
    expect(Array.isArray(corps.resultats)).toBe(true);
  });

  test("api_recherche_salle_tiret_retourne_200_sans_erreur", async ({
    request,
  }) => {
    const rep = await request.get("/api/salles?q=accor-arena");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(corps).toHaveProperty("resultats");
    expect(Array.isArray(corps.resultats)).toBe(true);
  });
});

test.describe("Scénario: catalogue — signalement salle manquante", () => {
  test("api_signaler_salleManquante_nomValide_retourne204", async ({
    request,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Consomme le quota rate-limit (3/h par IP) — testé sur chromium uniquement",
    );
    const rep = await request.post("/api/salles/signaler", {
      data: {
        nom: "Salle E2E Test",
        ville: "Paris",
        urlSite: "",
        commentaire: "",
      },
    });
    expect(rep.status()).toBe(204);
  });

  test("api_signaler_salleManquante_nomVide_retourne400", async ({
    request,
  }) => {
    const rep = await request.post("/api/salles/signaler", {
      data: { nom: "", ville: "", urlSite: "", commentaire: "" },
    });
    expect(rep.status()).toBe(400);
    const corps = await rep.json();
    expect(corps).toHaveProperty("erreur");
  });

  test("api_signaler_salleManquante_nomTropLong_retourne400", async ({
    request,
  }) => {
    const rep = await request.post("/api/salles/signaler", {
      data: { nom: "x".repeat(201), ville: "", urlSite: "", commentaire: "" },
    });
    expect(rep.status()).toBe(400);
  });

  test("salles_rechercheAucunResultat_boutonSignalerVisible", async ({
    page,
  }) => {
    await page.goto("/salles");
    await page.fill("input#q-salle", "SalleInexistanteXYZ987");
    await page.getByRole("button", { name: "Rechercher" }).click();
    // Même chemin de code (SalleService.lister, appel Nominatim/Ticketmaster) que
    // salles_ajouterEtSupprimer_fluxComplet_toastSuccesAuDeux ci-dessus, qui utilise 25s — aligné
    // pour la même marge, un budget plus court sur le même pire cas n'a pas de justification.
    await expect(
      page.getByRole("button", { name: "Signaler cette salle" }),
    ).toBeVisible({ timeout: 25000 });
  });
});

test.describe("Scénario: découverte — rate-limit fetch global (#806)", () => {
  // POST /api/decouverte/fetcher est déjà restreint à ROLE_ADMIN (SecurityConfiguration, depuis
  // 2.5.0) — ce n'est donc pas n'importe quel utilisateur qui pouvait le déclencher, contrairement
  // à la description initiale du ticket. Le rate-limit reste une défense en profondeur utile :
  // ignore requete.depts() et déclenche le même fetch global que le bouton admin "Relance
  // manuelle globale", sans quoi un admin cliquant plusieurs fois de suite (ou un script)
  // pouvait consommer le quota TM inutilement. Bucket partagé par tous les appelants (pas par
  // IP) : restreint à chromium pour ne pas l'épuiser depuis plusieurs navigateurs en parallèle.
  test.use({ storageState: FICHIER_AUTH_ADMIN });

  test("api_decouverte_fetcher_appelsRepetes_retourne429", async ({
    request,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Bucket partagé (1 / 5 min, tous appelants confondus) — testé sur chromium uniquement",
    );
    const premier = await request.post("/api/decouverte/fetcher", {
      data: { depts: [] },
    });
    expect([200, 429, 503]).toContain(premier.status());

    const deuxieme = await request.post("/api/decouverte/fetcher", {
      data: { depts: [] },
    });
    expect(deuxieme.status()).toBe(429);
  });
});

test.describe("Scénario: catalogue — masquage concerts", () => {
  test.beforeEach(async ({ request }, testInfo) => {
    if (testInfo.project.name !== "firefox") return;
    const rep = await request.get("/api/accueil?masques=true");
    if (!rep.ok()) return;
    const corps = (await rep.json()) as {
      artistesGroupes: { concerts: { id: string }[] }[];
    };
    for (const groupe of corps.artistesGroupes ?? []) {
      for (const concert of groupe.concerts ?? []) {
        await request.patch(`/api/spectacles/matches/${concert.id}`, {
          data: { masquer: false },
        });
      }
    }
  });

  // Annuler la confirmation ne masque rien : aucun appel, le concert reste affiché.
  test("accueil_masquerConcertPuisAnnuler_concertToujoursAffiche", async ({
    page,
  }) => {
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes("/api/accueil") && r.status() === 200,
      ),
      page.goto("/"),
    ]);
    const lignes = page.locator(".concert-ligne");
    const nbAvant = await lignes.count();
    test.skip(nbAvant === 0, "Aucun concert sur l'accueil de e2euser");
    const appels: string[] = [];
    page.on("request", (r) => {
      if (r.url().includes("/api/spectacles/")) appels.push(r.url());
    });

    await page
      .locator('button[aria-label="Masquer ce concert"]')
      .first()
      .click();
    const confirmation = page.locator("#dialogue-confirmation");
    await expect(confirmation).toBeVisible();
    await confirmation.getByRole("button", { name: "Annuler" }).click();

    await expect(confirmation).toBeHidden();
    await expect(lignes).toHaveCount(nbAvant);
    expect(appels).toEqual([]);
  });

  // L'état du filtre suit la réponse du serveur (préférence comprise) et chaque bascule envoie
  // une valeur explicite.
  test("accueil_filtreIndisponibles_etatSuitLeServeurEtBasculeExplicite", async ({
    page,
  }) => {
    const [reponse] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes("/api/accueil") && r.status() === 200,
      ),
      page.goto("/"),
    ]);
    const donnees = await reponse.json();
    const chip = page.locator(".chips a", { hasText: "Indisponibles" });
    if (donnees.inclureIndisponibles) {
      await expect(chip).toHaveClass(/actif/);
    } else {
      await expect(chip).not.toHaveClass(/actif/);
    }

    const valeurAttendue = donnees.inclureIndisponibles ? "0" : "1";
    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes("/api/accueil") &&
          r.url().includes(`indisponibles=${valeurAttendue}`),
      ),
      chip.click(),
    ]);
    if (donnees.inclureIndisponibles) {
      await expect(chip).not.toHaveClass(/actif/);
    } else {
      await expect(chip).toHaveClass(/actif/);
    }
  });

  test("accueil_masquerPuisDemasquerConcert_disparaitPuisReapparait", async ({
    page,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "firefox",
      "Modifie user_matches partagé — concurrence avec les autres navigateurs en parallèle",
    );
    const [,] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes("/api/accueil") && r.status() === 200,
      ),
      page.goto("/"),
    ]);
    await expect(page.locator(".etat-chargement")).not.toBeVisible({
      timeout: 3000,
    });

    const nbConcerts = await page.locator(".concert-ligne").count();
    expect(
      nbConcerts,
      "Précondition : aucun concert visible sur la page d'accueil — vérifier que e2euser suit des artistes/salles et que les jobs de fetch ont tourné",
    ).toBeGreaterThan(0);

    const btnMasquer = page
      .locator('button[aria-label="Masquer ce concert"]')
      .first();
    await expect(btnMasquer).toBeVisible({ timeout: 3000 });
    await btnMasquer.click();
    const confirmation = page.locator("#dialogue-confirmation");
    await expect(confirmation).toContainText("Masqués");
    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes("/api/spectacles/matches/") && r.status() === 200,
      ),
      confirmation.getByRole("button", { name: "Masquer" }).click(),
    ]);

    const [,] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes("/api/accueil") &&
          r.url().includes("masques=true") &&
          r.status() === 200,
      ),
      page.goto("/?masques=true"),
    ]);
    await expect(page.locator(".concert-ligne").first()).toBeVisible({
      timeout: 5000,
    });

    const btnDemasquer = page
      .locator(
        'button[aria-label="Réafficher ce concert"], [data-reafficher-id]',
      )
      .first();
    await expect(btnDemasquer).toBeVisible({ timeout: 3000 });
    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes("/api/spectacles/matches/") && r.status() === 200,
      ),
      btnDemasquer.click(),
    ]);

    const [,] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes("/api/accueil") &&
          !r.url().includes("masques=true") &&
          r.status() === 200,
      ),
      page.goto("/"),
    ]);
    await expect(page.locator(".concert-ligne").first()).toBeVisible({
      timeout: 5000,
    });
  });
});

// #818 : masquerArtiste/demasquerArtiste filtrent désormais sur artisteId (référentiel) plutôt
// qu'artisteNorm (texte par source) — vérifie que le flux group-level fonctionne toujours avec
// le nouveau contrat API, et qu'un artisteId invalide est bien rejeté en 400.
test.describe("Scénario: accueil — action Masquer reconnaissable", () => {
  // « Masquer » est une action secondaire : plus petite que « Billets », et soulignée pour
  // être reconnue comme cliquable sans survol (tactile).
  test("accueil_masquer_plusPetitQueBillets_etSouligne", async ({ page }) => {
    await page.goto("/");
    const ligne = page.locator(".concert-ligne").first();
    await expect(ligne).toBeVisible({ timeout: 15000 });
    const masquer = ligne.locator('button[aria-label="Masquer ce concert"]');
    const billets = ligne.getByRole("link", { name: "Billets" });
    await expect(masquer).toBeVisible();
    const style = (el: typeof masquer) =>
      el.evaluate((n) => {
        const c = getComputedStyle(n);
        return {
          taille: parseFloat(c.fontSize),
          soulignement: c.textDecorationLine,
        };
      });
    const styleMasquer = await style(masquer);
    if ((await billets.count()) > 0) {
      const styleBillets = await billets.first().evaluate((n) => {
        return parseFloat(getComputedStyle(n).fontSize);
      });
      expect(styleMasquer.taille).toBeLessThan(styleBillets);
    }
    expect(styleMasquer.soulignement).toContain("underline");
  });
});

test.describe("Scénario: catalogue — masquage par artiste (#818)", () => {
  test("accueil_masquerPuisDemasquerArtiste_disparaitPuisReapparait", async ({
    page,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "firefox",
      "Modifie user_matches partagé — concurrence avec les autres navigateurs en parallèle",
    );
    const repAccueil = await page.request.get("/api/accueil");
    expect(repAccueil.ok()).toBeTruthy();
    const corps = (await repAccueil.json()) as {
      artistesGroupes: { artisteId: string }[];
    };
    const groupe = (corps.artistesGroupes ?? [])[0];
    expect(
      groupe,
      "Précondition : au moins un groupe d'artiste visible sur l'accueil — vérifier que e2euser suit des artistes et que les jobs de fetch ont tourné",
    ).toBeTruthy();

    let masque = false;
    try {
      const repMasquer = await page.request.post(
        "/api/spectacles/masquer-artiste",
        { data: { artisteId: groupe.artisteId } },
      );
      expect(repMasquer.ok()).toBeTruthy();
      masque = true;

      const repApres = await page.request.get("/api/accueil");
      expect(repApres.ok()).toBeTruthy();
      const corpsApres = (await repApres.json()) as {
        artistesGroupes: { artisteId: string }[];
      };
      expect(
        (corpsApres.artistesGroupes ?? []).some(
          (g) => g.artisteId === groupe.artisteId,
        ),
      ).toBe(false);
    } finally {
      if (masque) {
        await page.request.post("/api/spectacles/demasquer-artiste", {
          data: { artisteId: groupe.artisteId },
        });
      }
    }
  });

  test("api_masquerArtiste_artisteIdInvalide_retourne400", async ({
    request,
  }) => {
    const rep = await request.post("/api/spectacles/masquer-artiste", {
      data: { artisteId: "pas-un-uuid" },
    });
    expect(rep.status()).toBe(400);
  });

  test("api_demasquerArtiste_artisteIdManquant_retourne400", async ({
    request,
  }) => {
    const rep = await request.post("/api/spectacles/demasquer-artiste", {
      data: {},
    });
    expect(rep.status()).toBe(400);
  });
});

test.describe("Scénario: accueil — nettoyage du paramètre ancre après scroll (#801)", () => {
  // Avant #801, l'ancre survivait à tout changement de filtre suivant (basculerFiltre conserve
  // route.query par spread), re-déclenchant un scroll forcé à chaque bascule de chip.
  test("accueil_bascculerFiltreApresAncre_retireAncreDeLurl", async ({
    page,
  }) => {
    const [,] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes("/api/accueil") && r.status() === 200,
      ),
      page.goto("/"),
    ]);
    const premierGroupe = page.locator(".concert-groupe").first();
    await expect(
      premierGroupe,
      "Précondition : au moins un artiste suivi avec concert visible sur l'accueil",
    ).toBeVisible({ timeout: 5000 });
    const idGroupe = await premierGroupe.getAttribute("id");
    expect(idGroupe).toBeTruthy();

    // scrollVersAncre() retire l'ancre dès le premier scroll (onMounted, #801) — pas seulement
    // au changement de filtre suivant. On attend donc directement la disparition plutôt que
    // d'asserter sa présence transitoire juste après le goto (racy : la suppression peut déjà
    // avoir eu lieu au moment où l'assertion s'exécute, notamment sur Firefox).
    await page.goto(`/?ancre=${idGroupe}`);
    await expect(page).not.toHaveURL(/ancre=/, { timeout: 5000 });

    // Bascule de filtre suivante : l'ancre, déjà retirée, ne doit pas réapparaître.
    await page.locator(".chips a", { hasText: "Nouveau" }).click();
    await expect(page).toHaveURL(/nouveau=true/);
    await expect(page).not.toHaveURL(/ancre=/);

    await page.locator(".chips a", { hasText: "Nouveau" }).click();
    await expect(page).not.toHaveURL(/nouveau=true/);
    await expect(page).not.toHaveURL(/ancre=/);
  });
});
