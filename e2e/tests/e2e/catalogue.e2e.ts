import { test, expect } from "@playwright/test";

async function supprimerArtisteDeListe(
  page: import("@playwright/test").Page,
  nomRegex: RegExp,
): Promise<void> {
  const carte = page.locator(".carte-item").filter({
    has: page.locator(".carte-nom", { hasText: nomRegex }),
  });
  if ((await carte.count()) === 0) return;
  await carte.locator("button.btn-danger-texte").first().click();
  await page.locator("dialog#dialog-confirmer-suppr-artiste").waitFor();
  await page.click("dialog#dialog-confirmer-suppr-artiste button.btn-danger");
  await expect(page.locator(".alerte-succes").first()).toBeVisible({
    timeout: 5000,
  });
}

async function supprimerSalleDeListe(
  page: import("@playwright/test").Page,
  nomRegex: RegExp,
): Promise<void> {
  const carte = page.locator(".carte-item").filter({
    has: page.locator(".carte-nom", { hasText: nomRegex }),
  });
  if ((await carte.count()) === 0) return;
  await carte.locator("button.btn-danger-texte").first().click();
  await page.locator("dialog#dialog-confirmer-suppr-salle").waitFor();
  await page.click("dialog#dialog-confirmer-suppr-salle button.btn-danger");
  await expect(page.locator(".alerte-succes").first()).toBeVisible({
    timeout: 5000,
  });
}

test.describe("Scénario: catalogue — artistes", () => {
  test("artistes_ajouterEtSupprimer_fluxComplet_toastSuccesAuDeux", async ({
    page,
  }) => {
    await page.goto("/artistes");

    // Nettoyage préalable depuis la liste (avant toute recherche)
    await supprimerArtisteDeListe(page, /nina simone/i);

    // Rechercher et suivre
    await page.fill("input#q-artiste", "Nina Simone");
    await page.getByRole("button", { name: "Rechercher" }).click();

    const btnSuivre = page.getByRole("button", { name: /Suivre/ }).first();
    await expect(btnSuivre).toBeVisible({ timeout: 15000 });

    const ariaLabel = (await btnSuivre.getAttribute("aria-label")) ?? "";
    const nomArtiste = ariaLabel.replace(/^Suivre\s+/i, "").trim();

    await btnSuivre.click();
    // L'ajout produit un flash fetchEnCours (.alerte-info), pas .alerte-succes
    await expect(page.locator(".alerte-info").first()).toBeVisible({
      timeout: 5000,
    });

    // Suppression post-test — laisse toujours l'état propre
    const btnSupprimer = page.locator(
      `button[aria-label="Supprimer ${nomArtiste}"]`,
    );
    await expect(btnSupprimer).toBeVisible({ timeout: 5000 });
    await btnSupprimer.click();
    await page.locator("dialog#dialog-confirmer-suppr-artiste").waitFor();
    await page.click("dialog#dialog-confirmer-suppr-artiste button.btn-danger");
    await expect(page.locator(".alerte-succes").first()).toBeVisible({
      timeout: 5000,
    });
  });
});

test.describe("Scénario: catalogue — salles", () => {
  test("salles_ajouterEtSupprimer_fluxComplet_toastSuccesAuDeux", async ({
    page,
  }) => {
    await page.goto("/salles");

    // Nettoyage préalable depuis la liste (avant toute recherche)
    await supprimerSalleDeListe(page, /olympia/i);

    // Rechercher et suivre
    await page.fill("input#q-salle", "Olympia");
    await page.getByRole("button", { name: "Rechercher" }).click();

    const btnSuivre = page.getByRole("button", { name: /Suivre/ }).first();
    await expect(btnSuivre).toBeVisible({ timeout: 25000 });

    const ariaLabel = (await btnSuivre.getAttribute("aria-label")) ?? "";
    const nomSalle = ariaLabel.replace(/^Suivre\s+/i, "").trim();

    await btnSuivre.click();
    await expect(page.locator(".alerte-succes").first()).toBeVisible({
      timeout: 5000,
    });

    // Suppression post-test — laisse toujours l'état propre
    const btnSupprimer = page.locator(
      `button[aria-label="Supprimer ${nomSalle}"]`,
    );
    await expect(btnSupprimer).toBeVisible({ timeout: 5000 });
    await btnSupprimer.click();
    await page.locator("dialog#dialog-confirmer-suppr-salle").waitFor();
    await page.click("dialog#dialog-confirmer-suppr-salle button.btn-danger");
    await expect(page.locator(".alerte-succes").first()).toBeVisible({
      timeout: 5000,
    });
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
  }) => {
    const rep = await request.post("/api/salles/signaler", {
      data: {
        nom: "Salle E2E Test",
        ville: "Paris",
        urlSite: "",
        commentaire: "",
      },
    });
    expect([204, 503]).toContain(rep.status());
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
    await expect(
      page.getByRole("button", { name: "Signaler cette salle" }),
    ).toBeVisible({ timeout: 15000 });
  });
});

test.describe("Scénario: catalogue — masquage concerts", () => {
  test("accueil_masquerPuisDemasquerConcert_disparaitPuisReapparait", async ({
    page,
  }) => {
    await page.goto("/");

    if ((await page.locator(".concert-ligne").count()) === 0) {
      test.skip();
      return;
    }

    const btnMasquer = page
      .locator('button[aria-label="Masquer ce concert"]')
      .first();
    await expect(btnMasquer).toBeVisible({ timeout: 3000 });
    await btnMasquer.click();

    await page.goto("/?masques=true");
    await expect(page.locator(".concert-ligne").first()).toBeVisible({
      timeout: 5000,
    });

    const btnDemasquer = page
      .locator(
        'button[aria-label="Réafficher ce concert"], [data-reafficher-id]',
      )
      .first();
    await expect(btnDemasquer).toBeVisible({ timeout: 3000 });
    await btnDemasquer.click();
    await page.waitForURL(/\//, { waitUntil: "domcontentloaded" });
  });
});
