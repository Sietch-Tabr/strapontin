import { test, expect } from "@playwright/test";

test.describe("Scénario: authentification et gardes de route", () => {
  test("accueil_charge_si_authentifie", async ({ page }) => {
    await page.goto("/");
    await expect(page).not.toHaveURL(/login/);
    await expect(page.locator("main")).toBeVisible();
  });

  test("route_protegee_redirige_vers_login_sans_session", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/spectacles");
    await expect(page).toHaveURL(/login/);
    await ctx.close();
  });

  test("route_decouverte_redirige_vers_login_sans_session", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/decouverte");
    await expect(page).toHaveURL(/login/);
    await ctx.close();
  });

  test("route_admin_redirige_vers_login_sans_session", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/admin");
    await expect(page).toHaveURL(/login/);
    await ctx.close();
  });

  test("route_admin_accessible_avec_compte_admin", async ({ page }) => {
    const response = await page.goto("/admin");
    expect(response?.status()).not.toBe(500);
    await expect(page).not.toHaveURL(/login/);
    await expect(page.locator("main")).toBeVisible();
  });

  test("page_login_accessible_sans_erreur", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    const response = await page.goto("/login");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("form")).toBeVisible();
    await ctx.close();
  });

  test("login_invalide_affiche_erreur_sans_planter", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/login");
    await page.fill("input[name='identifiant']", "utilisateur_inexistant");
    await page.fill("input[name='motDePasse']", "motdepasse_faux_12chars");
    const [reponse] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes("/login") && r.request().method() === "POST",
      ),
      page.click("button[type='submit']"),
    ]);
    // En mode hybride (AuthViewController Thymeleaf actif pendant la migration) :
    // POST /login invalide retourne 200 avec la page login + message d'erreur.
    expect(reponse.status()).toBe(200);
    await expect(page.locator("form")).toBeVisible();
    await ctx.close();
  });

  test("setup_redirige_vers_login_si_deja_configure", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/setup");
    await expect(page).toHaveURL(/login/);
    await ctx.close();
  });

  test("api_auth_me_retourne_utilisateur_courant", async ({ request }) => {
    const rep = await request.get("/api/auth/me");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(corps).toHaveProperty("identifiant");
    expect(corps).toHaveProperty("role");
  });

  test("api_auth_me_retourne_401_sans_session", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const req = await ctx.request.get("/api/auth/me");
    expect(req.status()).toBe(401);
    await ctx.close();
  });
});

test.describe("Scénario: cookie de session — durée", () => {
  test("cookie_session_non_expire_et_duree_max_30_jours", async ({
    context,
  }) => {
    await context.request.get("/");
    const cookies = await context.cookies();
    const cookie = cookies.find((c) => c.name === "SESSION");
    expect(cookie).toBeDefined();
    const maintenant = Date.now() / 1000;
    expect(cookie!.expires).toBeGreaterThan(maintenant);
    expect(cookie!.expires).toBeLessThan(maintenant + 31 * 24 * 3600);
  });
});
