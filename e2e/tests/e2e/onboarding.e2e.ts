import { test, expect, request as playwrightRequest } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

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
    const { utilisateurs: users } = await reps.json();
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

/**
 * Crée un compte invité, l'active via reset-password (connecte la session) et retourne une
 * page authentifiée pour ce nouveau compte — état "premier pas" garanti (aucun artiste/salle
 * suivi, aucune notification activée, onboarding jamais vu), contrairement au compte e2euser
 * partagé dont l'état dépend des autres tests de la suite. Restreint à chromium (précédent
 * établi par invitation.e2e.ts pour toute création de compte de test).
 */
async function creerCompteFraisEtSeConnecter(
  browser: import("@playwright/test").Browser,
): Promise<{
  page: import("@playwright/test").Page;
  ctx: import("@playwright/test").BrowserContext;
}> {
  const emailTest = `e2e-onboarding-${Date.now()}@test.local`;
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
      test.skip(true, "Service email configuré : lien non retourné par l'API");
    }
    lienInvitation = corps.lienReset as string;
  } finally {
    await apiCtx.dispose();
  }

  const identifiantTest = `e2eonb${Date.now()}`.slice(0, 30);
  const ctx = await browser.newContext({ storageState: undefined });
  const page = await ctx.newPage();
  await page.goto(lienInvitation);
  await expect(page.locator('input[name="identifiant"]')).toBeVisible({
    timeout: 5000,
  });
  await page.fill('input[name="identifiant"]', identifiantTest);
  await page.fill('input[name="motDePasse"]', "TestE2E!2026onboard");
  await page.click('button[type="submit"]');
  await page.waitForURL((url) => !url.pathname.startsWith("/reset"), {
    timeout: 5000,
  });

  return { page, ctx };
}

test.describe("Scénario: modale d'onboarding — nouveau compte", () => {
  test("onboarding_nouveauCompte_modaleAfficheeAuChargement", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Création de compte — chromium uniquement",
    );

    const { page, ctx } = await creerCompteFraisEtSeConnecter(browser);
    try {
      await expect(page.locator("dialog#dialog-onboarding")).toBeVisible({
        timeout: 5000,
      });
    } finally {
      await ctx.close();
    }
  });

  test("onboarding_navigationEtapes_suivantPrecedentFonctionnent", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Création de compte — chromium uniquement",
    );

    const { page, ctx } = await creerCompteFraisEtSeConnecter(browser);
    try {
      const dialog = page.locator("dialog#dialog-onboarding");
      await expect(dialog).toBeVisible({ timeout: 5000 });
      await expect(dialog).toContainText("Suivi artistes et salles");

      await dialog.getByRole("button", { name: "Suivant" }).click();
      await expect(dialog).toContainText("Filtre par département");

      await dialog.getByRole("button", { name: "Précédent" }).click();
      await expect(dialog).toContainText("Suivi artistes et salles");
    } finally {
      await ctx.close();
    }
  });

  test("onboarding_terminer_masqueEtNeReapparaitPasApresRechargement", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Création de compte — chromium uniquement",
    );

    const { page, ctx } = await creerCompteFraisEtSeConnecter(browser);
    try {
      const dialog = page.locator("dialog#dialog-onboarding");
      await expect(dialog).toBeVisible({ timeout: 5000 });

      // 4 cartes : 3 clics "Suivant" pour atteindre la dernière, puis "Terminer".
      for (let i = 0; i < 3; i++) {
        await dialog.getByRole("button", { name: "Suivant" }).click();
      }
      await dialog.getByRole("button", { name: "Terminer" }).click();
      await expect(dialog).not.toBeVisible({ timeout: 3000 });

      await page.reload();
      await expect(dialog).not.toBeVisible({ timeout: 3000 });
    } finally {
      await ctx.close();
    }
  });

  test("onboarding_boutonRevoirDansAide_reouvreLaModale", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Création de compte — chromium uniquement",
    );

    const { page, ctx } = await creerCompteFraisEtSeConnecter(browser);
    try {
      const dialog = page.locator("dialog#dialog-onboarding");
      await expect(dialog).toBeVisible({ timeout: 5000 });
      await dialog.locator(".bouton-icone").click();
      await expect(dialog).not.toBeVisible({ timeout: 3000 });

      await page.goto("/aide");
      await page
        .getByRole("button", { name: "Revoir la présentation de bienvenue" })
        .click();
      await expect(dialog).toBeVisible({ timeout: 3000 });
    } finally {
      await ctx.close();
    }
  });
});

test.describe("Scénario: checklist premiers pas — /aide", () => {
  test("checklist_nouveauCompte_troisItemsNonCoches", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Création de compte — chromium uniquement",
    );

    const { page, ctx } = await creerCompteFraisEtSeConnecter(browser);
    try {
      await page.goto("/aide");
      const checklist = page.locator(".checklist-premiers-pas");
      await expect(checklist).toBeVisible({ timeout: 5000 });
      await expect(checklist.locator(".checklist-marque--fait")).toHaveCount(0);
    } finally {
      await ctx.close();
    }
  });

  // ChecklistPremiersPas.vue ne fetch qu'au montage (pas de polling) : l'item ne se coche donc
  // qu'à la prochaine visite de la page, pas en direct pendant que l'utilisateur y est déjà.
  test("checklist_departementsConfigures_itemCocheALaProchaineVisite", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Création de compte — chromium uniquement",
    );

    const { page, ctx } = await creerCompteFraisEtSeConnecter(browser);
    try {
      await page.goto("/aide");
      const checklist = page.locator(".checklist-premiers-pas");
      await expect(checklist).toBeVisible({ timeout: 5000 });

      await page.request.patch("/api/user/config/departements", {
        data: { departements: "75" },
      });
      await page.goto("/aide");
      await expect(checklist.locator(".checklist-item--fait")).toContainText(
        "Configurer vos départements",
      );
    } finally {
      await ctx.close();
    }
  });
});

test.describe("Scénario: nudge notifications — premier ajout", () => {
  test("nudge_premierAjoutArtiste_afficheToastNotifications", async ({
    browser,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "Création de compte — chromium uniquement",
    );

    const { page, ctx } = await creerCompteFraisEtSeConnecter(browser);
    try {
      await page.goto("/artistes");
      // page.goto (rechargement complet) redéclenche la vérification onboarding à chaque fois
      // tant que la modale n'a pas été fermée — un dialog natif ouvert rend le reste de la page
      // inerte (formulaire de recherche inutilisable) sans elle.
      // verifierOnboarding() (App.vue) est asynchrone : la modale peut s'ouvrir un instant après
      // la résolution de page.goto — un simple isVisible() immédiat raterait la fenêtre où elle
      // apparaît, laissant le dialog s'ouvrir APRÈS la tentative de clic/fill et la bloquer quand
      // même. Attendre explicitement son apparition (ou son absence confirmée) avant de continuer.
      const dialogOnboarding = page.locator("dialog#dialog-onboarding");
      await dialogOnboarding
        .waitFor({ state: "visible", timeout: 3000 })
        .catch(() => {});
      if (await dialogOnboarding.isVisible().catch(() => false)) {
        await dialogOnboarding.locator(".bouton-icone").click();
        await expect(dialogOnboarding).not.toBeVisible({ timeout: 3000 });
      }
      await page.fill("#q-artiste", "Radiohead");
      await page.getByRole("button", { name: "Rechercher" }).click();
      await expect(page.locator(".section-resultats")).toBeVisible({
        timeout: 10000,
      });

      // Deux issues possibles selon la disponibilité de suggestions externes (Wikidata/Deezer) —
      // les deux mènent au même code d'ajout (ajouter()).
      const boutonSuivre = page.locator('button[aria-label^="Suivre"]').first();
      const boutonAjouterQuandMeme = page.getByRole("button", {
        name: /Ajouter «.*» quand même/,
      });
      await Promise.race([
        boutonSuivre.click({ timeout: 8000 }),
        boutonAjouterQuandMeme.click({ timeout: 8000 }),
      ]).catch(() => {});

      await expect(
        page
          .locator(".alerte")
          .filter({ hasText: "activez les notifications" }),
      ).toBeVisible({ timeout: 10000 });
    } finally {
      await ctx.close();
    }
  });
});
