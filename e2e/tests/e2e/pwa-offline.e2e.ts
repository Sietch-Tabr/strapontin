import { test, expect } from "@playwright/test";

// #766 : premier lancement PWA hors-ligne avant toute synchronisation — le document HTML charge
// mais le bundle JS principal échoue (service worker pas encore actif / réseau coupé juste
// après). Sans le filet de secours inline (front/index.html), #app reste vide indéfiniment :
// écran noir sans message. Simulé ici en bloquant les assets JS tout en laissant le document
// charger normalement — reproduit fidèlement l'échec de fetch décrit dans le ticket sans
// dépendre du cache réel du service worker (non déterministe en E2E).
test.describe("Scénario: PWA — filet de secours hors-ligne au premier lancement", () => {
  test("pwa_bundleJsInaccessible_afficheMessageHorsLigneApresDelai", async ({
    page,
  }) => {
    await page.route("**/assets/**", (route) => route.abort());
    await page.route("**/src/main.js", (route) => route.abort());

    await page.goto("/");

    // #app doit rester vide tant que le délai n'est pas écoulé (pas de faux positif immédiat)
    await expect(page.locator("#app")).toBeEmpty();

    await expect(
      page.getByRole("heading", { name: "Vous êtes hors ligne" }),
    ).toBeVisible({ timeout: 6000 });
    await expect(
      page.getByRole("button", { name: "Réessayer" }),
    ).toBeVisible();
  });
});
