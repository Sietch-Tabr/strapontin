import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";

// Token prédéfini injecté par seed-e2e.sh pour e2e-invite@test.local
// SHA-256("e2e-invite-token-2026") = db2b2442...
const LIEN_INVITE = `${BASE_URL}/reset-password?token=e2e-invite-token-2026`;

test.describe("Scénario: invitation — dialog de confirmation", () => {
  test("invitation_creerUtilisateur_dialogAfficheEmail", async ({
    page,
    isMobile,
  }) => {
    const emailTest = `e2e-dialog-${Date.now()}@test.local`;

    await page.goto("/admin");
    if (isMobile) {
      await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
    } else {
      await page.locator('a[href="#utilisateurs"]').click();
    }
    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');

    const dialog = page.locator("dialog#dialog-invitation");
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await expect(dialog).toContainText(emailTest);
    await page.click("button#btn-fermer-invitation");

    // Nettoyage
    const carteUser = page
      .locator(".user-carte")
      .filter({ hasText: emailTest });
    await expect(carteUser).toBeVisible({ timeout: 3000 });
    await carteUser.locator("button.btn-danger").click();
    await expect(page.locator("dialog#dialog-supprimer-user")).toBeVisible({
      timeout: 3000,
    });
    await page.click("button#dialog-supprimer-user-confirmer");
    await expect(
      page.locator(".user-carte").filter({ hasText: emailTest }),
    ).not.toBeVisible({ timeout: 5000 });
  });
});

test.describe("Scénario: invitation — flux activation", () => {
  // Token et utilisateur créés par seed-e2e.sh (e2e-invite@test.local + token prédéfini)
  // Le token est à usage unique : ne tester que sur un seul navigateur
  test("invitation_tokenValide_activerCompte_redirigeVersAccueil", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Token à usage unique — testé sur chromium uniquement",
    );
    const identifiantTest = `e2einv${Date.now()}`.slice(0, 30);

    const ctx = await browser.newContext({ storageState: undefined });
    const pageInvite = await ctx.newPage();
    await pageInvite.goto(LIEN_INVITE);

    await expect(pageInvite.locator('input[name="identifiant"]')).toBeVisible({
      timeout: 5000,
    });
    await pageInvite.fill('input[name="identifiant"]', identifiantTest);
    await pageInvite.fill('input[name="motDePasse"]', "TestE2E!2026invite");
    await pageInvite.click('button[type="submit"]');

    // Après activation réussie : redirection vers l'accueil (session ouverte)
    await pageInvite.waitForURL((url) => !url.pathname.startsWith("/reset"), {
      timeout: 5000,
    });
    expect(pageInvite.url()).not.toContain("/login");
    await ctx.close();
  });

  test("invitation_tokenInvalide_formulaireVisibleSansCrash", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const pageTest = await ctx.newPage();
    await pageTest.goto(`${BASE_URL}/reset-password?token=token-invalide-e2e`);

    // La page charge sans crash et affiche le formulaire (token invalide → mode reset)
    await expect(pageTest.locator("form, .alerte")).toBeVisible({
      timeout: 5000,
    });
    // Si le formulaire est visible, soumettre pour déclencher l'erreur
    const form = pageTest.locator("form");
    if (await form.isVisible()) {
      await pageTest.fill('input[name="motDePasse"]', "MotDePasseTest12");
      await pageTest.click('button[type="submit"]');
      await expect(pageTest.locator(".alerte-erreur, .alerte")).toBeVisible({
        timeout: 5000,
      });
    }
    await ctx.close();
  });
});
