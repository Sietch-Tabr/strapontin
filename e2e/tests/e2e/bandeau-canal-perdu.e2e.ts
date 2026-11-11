import { test, expect, type Page } from "@playwright/test";

// La logique serveur du canal perdu est testée en Java : ici, l'affichage du bandeau et son lien.
// L'interception de page.route est réservée à chromium (instabilité connue sous WebKit).
test.describe("Scénario: bandeau canal de notification perdu", () => {
  test.skip(
    ({ browserName, isMobile }) => browserName !== "chromium" || isMobile,
    "Réponse du serveur simulée : testé sur chromium desktop uniquement",
  );

  async function simulerEtat(
    page: Page,
    etat: { canalPerdu: boolean; pushJamaisActive: boolean },
  ) {
    await page.route("**/api/user/config/canal-perdu", (route) =>
      route.fulfill({ status: 200, json: etat }),
    );
  }

  test("bandeau_canalPerdu_annonceLArretEtMeneAuxNotifications", async ({
    page,
  }) => {
    await simulerEtat(page, { canalPerdu: true, pushJamaisActive: false });
    await page.goto("/");

    const bandeau = page.locator(".bandeau-canal-perdu");
    await expect(bandeau).toContainText("Vos notifications push sont arrêtées");
    await bandeau
      .getByRole("link", { name: "Réactiver mes notifications" })
      .click();
    await expect(page).toHaveURL(/\/parametres#notifications/);
  });

  test("bandeau_pushJamaisActive_annonceLAbsenceDeCanalEtMeneAuxNotifications", async ({
    page,
  }) => {
    await simulerEtat(page, { canalPerdu: false, pushJamaisActive: true });
    await page.goto("/");

    const bandeau = page.locator(".bandeau-canal-perdu");
    await expect(bandeau).toContainText(
      "Vos notifications push ne sont pas activées",
    );
    await bandeau
      .getByRole("link", { name: "Activer mes notifications" })
      .click();
    await expect(page).toHaveURL(/\/parametres#notifications/);
  });

  test("bandeau_aucunProbleme_neSAffichePas", async ({ page }) => {
    await simulerEtat(page, { canalPerdu: false, pushJamaisActive: false });
    await page.goto("/");

    await expect(page.locator("main")).toBeVisible();
    await expect(page.locator(".bandeau-canal-perdu")).toHaveCount(0);
  });
});
