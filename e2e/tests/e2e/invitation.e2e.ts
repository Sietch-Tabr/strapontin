import { test, expect, request as playwrightRequest } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";

const emailsANettoyer: string[] = [];

async function supprimerUtilisateurParEmail(email: string): Promise<void> {
  const ctx = await playwrightRequest.newContext({
    baseURL: BASE_URL,
    storageState: FICHIER_AUTH_ADMIN,
  });
  try {
    const reps = await ctx.get("/api/admin/utilisateurs");
    if (!reps.ok()) return;
    const users = await reps.json();
    const cible = users.find((u: { email: string }) => u.email === email);
    if (cible) await ctx.delete(`/api/admin/utilisateurs/${cible.id}`);
  } finally {
    await ctx.dispose();
  }
}

test.afterEach(async () => {
  const copies = emailsANettoyer.splice(0);
  for (const email of copies) {
    try {
      await supprimerUtilisateurParEmail(email);
    } catch {
      /* ignore */
    }
  }
});

test.describe("Scénario: invitation — dialog de confirmation", () => {
  test("invitation_creerUtilisateur_dialogAfficheEmail", async ({
    page,
    isMobile,
  }) => {
    const emailTest = `e2e-dialog-${Date.now()}@test.local`;
    emailsANettoyer.push(emailTest);

    await page.goto("/admin");
    if (isMobile) {
      await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
    } else {
      await page.locator('a[href="#utilisateurs"]').click();
    }
    await page.waitForLoadState("networkidle");

    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    await page.click('.form-creer-utilisateur button[type="submit"]');

    const dialog = page.locator("dialog#dialog-invitation");
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await expect(dialog).toContainText(emailTest);
    await page.click("button#btn-fermer-invitation");
    await expect(dialog).not.toBeVisible({ timeout: 3000 });
  });
});

test.describe("Scénario: invitation — flux activation", () => {
  // Le test crée sa propre invitation via l'API admin pour être indépendant du seed.
  // Restreint à chromium : le token est à usage unique.
  test("invitation_tokenValide_activerCompte_redirigeVersAccueil", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Token à usage unique — testé sur chromium uniquement",
    );

    const emailTest = `e2e-inv-${Date.now()}@test.local`;
    emailsANettoyer.push(emailTest);

    // Créer l'invitation via l'API admin
    const apiCtx = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: FICHIER_AUTH_ADMIN,
    });
    let lienInvitation: string;
    try {
      const rep = await apiCtx.post("/api/admin/utilisateurs", {
        data: { email: emailTest },
      });
      const corps = await rep.json();
      if (!corps.lienReset) {
        test.skip(
          true,
          "Service email configuré : lien non retourné par l'API",
        );
        return;
      }
      lienInvitation = corps.lienReset as string;
    } finally {
      await apiCtx.dispose();
    }

    const identifiantTest = `e2einv${Date.now()}`.slice(0, 30);
    const ctxNavigateur = await browser.newContext({ storageState: undefined });
    const pageInvite = await ctxNavigateur.newPage();
    await pageInvite.goto(lienInvitation);

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
    await ctxNavigateur.close();
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
