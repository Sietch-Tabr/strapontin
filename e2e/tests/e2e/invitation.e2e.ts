import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

test.describe("Scénario: invitation — flux complet", () => {
  test("invitation_creerCompte_fluxSetup_redirigeVersAccueil", async ({
    page,
    browser,
    isMobile,
  }) => {
    const emailTest = `e2e-invite-${Date.now()}@test.local`;
    const identifiantTest = `e2einvite${Date.now()}`.slice(0, 30);

    // Admin crée l'utilisateur
    await page.goto("/admin");
    if (isMobile) {
      await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
    } else {
      await page.locator('a[href="#utilisateurs"]').click();
    }
    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');

    // Dialog invitation s'ouvre, récupérer le lien
    const dialog = page.locator("dialog#dialog-invitation");
    await expect(dialog).toBeVisible({ timeout: 5000 });
    const lienInvitation = await page
      .locator("input#lien-invitation")
      .inputValue();
    expect(lienInvitation).toContain("/reset-password?token=");
    await page.click("button#btn-fermer-invitation");

    // Nouvelle session sans auth → flux activation
    const ctx = await browser.newContext({ storageState: undefined });
    const pageInvite = await ctx.newPage();
    await pageInvite.goto(lienInvitation);

    // La page détecte le type "invite" et affiche le champ identifiant
    await expect(
      pageInvite.locator('input[name="identifiant"]'),
    ).toBeVisible({ timeout: 5000 });
    await pageInvite.fill('input[name="identifiant"]', identifiantTest);
    await pageInvite.fill('input[name="motDePasse"]', "TestE2E!2026invite");
    await pageInvite.click('button[type="submit"]');

    // Après activation : redirection vers l'accueil (session ouverte)
    await expect(pageInvite).toHaveURL(/^\/((?!reset|login).)*$/, {
      timeout: 5000,
    });
    await ctx.close();

    // Nettoyage : supprimer le compte créé
    await page.goto("/admin");
    if (isMobile) {
      await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
    } else {
      await page.locator('a[href="#utilisateurs"]').click();
    }
    const carteUser = page.locator(".user-carte").filter({ hasText: emailTest });
    await expect(carteUser).toBeVisible({ timeout: 3000 });
    await carteUser.locator("button.btn-danger").click();
    await expect(
      page.locator("dialog#dialog-supprimer-user"),
    ).toBeVisible({ timeout: 3000 });
    await page.click("button#dialog-supprimer-user-confirmer");
    await expect(
      page.locator(".user-carte").filter({ hasText: emailTest }),
    ).not.toBeVisible({ timeout: 5000 });
  });

  test("invitation_tokenInvalide_affichePage404OuErreur", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const pageTest = await ctx.newPage();
    await pageTest.goto("/reset-password?token=token-invalide-e2e");

    // Soit le formulaire affiche une erreur, soit la page redirige
    await expect(
      pageTest
        .locator(".alerte-erreur, .alerte")
        .or(pageTest.locator("text=Lien invalide")),
    ).toBeVisible({ timeout: 5000 });
    await ctx.close();
  });
});
