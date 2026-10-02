import { test, expect } from "@playwright/test";

// Protection contre les requêtes forgées (Fetch Metadata, Origin, en-tête applicatif) : une
// requête modifiante qui ne vient pas du front de l'application est refusée en 403. L'endpoint
// utilisé répond 400 dès que la requête atteint le contrôleur, sans rien modifier.
test.describe("Scénario: protection contre les requêtes forgées", () => {
  const DESABONNEMENT_INVALIDE = { data: { endpoint: "" } };

  test("requete_du_front_atteint_le_controleur", async ({ request }) => {
    const rep = await request.delete("/api/push", DESABONNEMENT_INVALIDE);
    expect(rep.status()).toBe(400);
  });

  test("requete_d_un_sous_domaine_voisin_refusee_403", async ({ request }) => {
    const rep = await request.delete("/api/push", {
      ...DESABONNEMENT_INVALIDE,
      headers: { Origin: "https://domotique.kwaite.fr" },
    });
    expect(rep.status()).toBe(403);
  });

  test("requete_same_site_refusee_403", async ({ request }) => {
    const rep = await request.delete("/api/push", {
      ...DESABONNEMENT_INVALIDE,
      headers: { "Sec-Fetch-Site": "same-site" },
    });
    expect(rep.status()).toBe(403);
  });

  test("requete_sans_en_tete_applicatif_refusee_403", async ({ request }) => {
    const rep = await request.delete("/api/push", {
      ...DESABONNEMENT_INVALIDE,
      headers: { "X-Requested-With": "" },
    });
    expect(rep.status()).toBe(403);
  });
});

// Une réponse d'erreur non JSON (page d'erreur du reverse proxy pendant un redémarrage) est
// présentée comme une erreur du serveur, jamais comme une connexion absente.
test.describe("Scénario: réponse non JSON du serveur", () => {
  test("reinitialisation_reponse_html_502_message_serveur_et_non_reseau", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.route("**/api/auth/type-token**", (route) =>
      route.fulfill({ status: 200, json: { type: "reset" } }),
    );
    await page.route("**/api/auth/reset-password", (route) =>
      route.fulfill({
        status: 502,
        contentType: "text/html",
        body: "<html><body>502 Bad Gateway</body></html>",
      }),
    );
    await page.goto("/reset-password?token=jeton-de-test");
    await page.getByLabel("Nouveau mot de passe").fill("un-mot-de-passe-solide");
    await page.getByRole("button", { name: "Enregistrer" }).click();

    await expect(
      page.getByText(
        "Le serveur est momentanément indisponible. Réessayez dans un instant.",
      ),
    ).toBeVisible();
    await expect(page.getByText("Vérifiez votre connexion")).not.toBeVisible();
    await ctx.close();
  });
});
