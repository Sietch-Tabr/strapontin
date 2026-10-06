import { test, expect, request as playwrightRequest } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";
const LXC_DEV_HOTE = "root@192.168.20.78";

/** SHA-256 hex du token brut, identique à AuthService.sha256Hex côté serveur. */
function sha256Hex(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

/**
 * Antidate l'expiration d'un token de vérification en base (LXC dev uniquement).
 *
 * Aucun endpoint API ne permet de simuler un token expiré (J+7) — accès DB ponctuel via SSH
 * validé explicitement pour ce cas de test, sur une donnée de test éphémère.
 */
function antidaterExpirationToken(tokenHash: string): void {
  // SQL transmis via stdin (pas d'argument shell) pour éviter tout problème d'échappement
  // des guillemets simples PostgreSQL à travers deux niveaux de shell (local + SSH).
  const sql = `UPDATE verifications SET expires_at = now() - interval '1 day' WHERE value = '${tokenHash}';`;
  execFileSync("ssh", [LXC_DEV_HOTE, `su postgres -c "psql -d strapontin"`], {
    input: sql,
    timeout: 15000,
  });
}

const emailsANettoyer: string[] = [];

async function supprimerUtilisateurParEmail(email: string): Promise<void> {
  const ctx = await playwrightRequest.newContext({
    baseURL: BASE_URL,
    storageState: FICHIER_AUTH_ADMIN,
  });
  try {
    // paginé (50/page, tri par date de création croissante) — un compte tout juste créé est
    // toujours en dernière page dès que le total dépasse une page.
    for (let page = 0; ; page++) {
      const reps = await ctx.get(`/api/admin/utilisateurs?page=${page}`);
      if (!reps.ok()) return;
      const { utilisateurs: users, totalPages } = await reps.json();
      const cible = users.find((u: { email: string }) => u.email === email);
      if (cible) {
        await ctx.delete(`/api/admin/utilisateurs/${cible.id}`);
        return;
      }
      if (page >= (totalPages ?? 1) - 1) return;
    }
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
    await expect(page.locator("section#utilisateurs")).toBeVisible();

    await page.fill('.form-creer-utilisateur input[type="email"]', emailTest);
    const attendreCreation = page.waitForResponse(
      (r) =>
        r.url().includes("/api/admin/utilisateurs") &&
        r.request().method() === "POST",
    );
    await page.click('.form-creer-utilisateur button[type="submit"]');
    await attendreCreation;

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
    // Chaque champ porte sa propre consigne, annoncée et visible pendant la saisie.
    const champIdentifiant = pageInvite.locator('input[name="identifiant"]');
    const champMotDePasse = pageInvite.locator('input[name="motDePasse"]');
    await expect(champIdentifiant).toHaveAccessibleDescription(
      /De 3 à 30 caractères/,
    );
    await expect(champMotDePasse).toHaveAccessibleDescription(
      "Au moins 12 caractères.",
    );
    await pageInvite.fill('input[name="identifiant"]', identifiantTest);
    await expect(pageInvite.locator("#aide-identifiant")).toBeVisible();
    await pageInvite.fill('input[name="motDePasse"]', "TestE2E!2026invite");
    await pageInvite.click('button[type="submit"]');

    // Après activation réussie : redirection vers l'accueil (session ouverte)
    await pageInvite.waitForURL((url) => !url.pathname.startsWith("/reset"), {
      timeout: 5000,
    });
    expect(pageInvite.url()).not.toContain("/login");
    await ctxNavigateur.close();
  });

  // #877 : un token réellement expiré (J+7) doit être rejeté avec le même message qu'un token
  // invalide — aucun endpoint API ne permet de simuler l'expiration, DB antidatée via SSH
  // (donnée de test éphémère, cf. accès validé pour ce cas précis).
  test("invitation_tokenExpire_activationRefuseeCommeInvalide", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Manipulation DB ciblée — testé sur chromium uniquement",
    );

    const emailTest = `e2e-expire-${Date.now()}@test.local`;
    emailsANettoyer.push(emailTest);

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

    const token = new URL(lienInvitation).searchParams.get("token")!;
    antidaterExpirationToken(sha256Hex(token));

    const ctx = await browser.newContext({
      storageState: undefined,
      baseURL: BASE_URL,
    });
    try {
      const rep = await ctx.request.post("/api/auth/reset-password", {
        data: {
          token,
          motDePasse: "MotDePasseTest12",
          identifiant: `e2eexpire${Date.now()}`.slice(0, 30),
        },
      });
      expect(rep.status()).toBe(400);
      const corps = await rep.json();
      expect(corps.erreur).toContain("invalide ou expiré");
    } finally {
      await ctx.close();
    }
  });

  // #877 : une invitation jamais activée (mot de passe temporaire inconnu) doit se comporter
  // comme n'importe quel échec de connexion — même code et forme de réponse qu'un identifiant
  // inexistant, sans indiquer que le compte existe mais est inactif (pas de fuite d'information).
  // Restreint à chromium : 2 tentatives de login consomment le rate-limiter bucket4j partagé
  // (15/15min par IP) — répété sur 5 projets navigateurs depuis la même machine, les derniers
  // exécutés (mobile-chrome/mobile-safari) tombaient en 429 plutôt qu'en 401.
  test("invitation_compteJamaisActive_tentativeConnexionEchoueCommeIdentifiantInconnu", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "2 tentatives de login — chromium uniquement pour ne pas épuiser le rate-limiter partagé",
    );

    const emailTest = `e2e-inactif-${Date.now()}@test.local`;
    emailsANettoyer.push(emailTest);

    const apiCtx = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: FICHIER_AUTH_ADMIN,
    });
    let identifiantTemp: string;
    try {
      const rep = await apiCtx.post("/api/admin/utilisateurs", {
        data: { email: emailTest },
      });
      const corps = await rep.json();
      identifiantTemp = corps.identifiant as string;
    } finally {
      await apiCtx.dispose();
    }

    const ctx = await browser.newContext({
      storageState: undefined,
      baseURL: BASE_URL,
    });
    try {
      const repCompteInactif = await ctx.request.post("/api/auth/login", {
        data: {
          identifiant: identifiantTemp,
          motDePasse: "mauvaisMotDePasse123",
        },
      });
      const repIdentifiantInconnu = await ctx.request.post("/api/auth/login", {
        data: {
          identifiant: `inconnu-${Date.now()}`,
          motDePasse: "mauvaisMotDePasse123",
        },
      });

      expect(repCompteInactif.status()).toBe(repIdentifiantInconnu.status());
      expect(repCompteInactif.status()).toBe(401);
    } finally {
      await ctx.close();
    }
  });

  test("invitation_tokenInvalide_formulaireVisibleSansCrash", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({
      storageState: undefined,
      baseURL: BASE_URL,
    });
    const pageTest = await ctx.newPage();
    await pageTest.goto(`/reset-password?token=token-invalide-e2e`);

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
