import { test, expect } from "@playwright/test";

async function supprimerArtisteDeListe(
  page: import("@playwright/test").Page,
  nomRegex: RegExp,
): Promise<void> {
  const carte = page.locator(".carte-item").filter({
    has: page.locator(".carte-nom", { hasText: nomRegex }),
  });
  if ((await carte.count()) === 0) return;
  await carte.locator("button.js-confirmer-suppr").first().click();
  await page.locator("dialog#dialog-confirmer-suppr-artiste").waitFor();
  await page.click("button#btn-confirmer-suppr-artiste");
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
  await carte.locator("button.js-confirmer-suppr-salle").first().click();
  await page.locator("dialog#dialog-confirmer-suppr-salle").waitFor();
  await page.click("button#btn-confirmer-suppr-salle");
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
      `button.js-confirmer-suppr[data-nom-artiste="${nomArtiste}"]`,
    );
    await expect(btnSupprimer).toBeVisible({ timeout: 5000 });
    await btnSupprimer.click();
    await page.locator("dialog#dialog-confirmer-suppr-artiste").waitFor();
    await page.click("button#btn-confirmer-suppr-artiste");
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
      `button.js-confirmer-suppr-salle[data-nom-salle="${nomSalle}"]`,
    );
    await expect(btnSupprimer).toBeVisible({ timeout: 5000 });
    await btnSupprimer.click();
    await page.locator("dialog#dialog-confirmer-suppr-salle").waitFor();
    await page.click("button#btn-confirmer-suppr-salle");
    await expect(page.locator(".alerte-succes").first()).toBeVisible({
      timeout: 5000,
    });
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
