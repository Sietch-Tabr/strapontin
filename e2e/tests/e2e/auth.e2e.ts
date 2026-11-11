import { test, expect } from "@playwright/test";

const E2E_USER_IDENTIFIANT = "e2euser";
const E2E_USER_MOT_DE_PASSE =
  process.env["E2E_USER_MOT_DE_PASSE"] ?? "e2e-user-2026";

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

test.describe("Scénario: API auth — endpoints Vue", () => {
  test("login_invalide_affiche_erreur_sans_planter", async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: undefined,
      extraHTTPHeaders: { "X-Real-IP": "192.0.2.10" },
    });
    const page = await ctx.newPage();
    await page.goto("/login");
    await page.fill("input[name='identifiant']", "utilisateur_inexistant");
    await page.fill("input[name='motDePasse']", "motdepasse_faux_12chars");
    const [reponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes("/api/auth/login") &&
          r.request().method() === "POST",
      ),
      page.click("button[type='submit']"),
    ]);
    expect(reponse.status()).toBe(401);
    await expect(page.locator("form")).toBeVisible();
    await ctx.close();
  });

  test("api_login_credentials_invalides_retourne_401", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const rep = await ctx.request.post("/api/auth/login", {
      data: { identifiant: "inexistant", motDePasse: "motdepassefaux12" },
    });
    expect(rep.status()).toBe(401);
    await ctx.close();
  });

  test("api_forgot_password_retourne_200_email_inconnu", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const rep = await ctx.request.post("/api/auth/forgot-password", {
      data: { email: "inconnu@inexistant.fr" },
    });
    expect(rep.status()).toBe(200);
    await ctx.close();
  });

  test("api_reset_password_token_invalide_retourne_400", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const rep = await ctx.request.post("/api/auth/reset-password", {
      data: {
        token: "token_bidon_inexistant",
        motDePasse: "nouveaumdp12chars",
      },
    });
    expect(rep.status()).toBe(400);
    await ctx.close();
  });

  test("api_totp_sans_challenge_retourne_409", async ({ request }) => {
    const rep = await request.post("/api/auth/totp", {
      data: { code: "123456" },
    });
    expect(rep.status()).toBe(409);
  });

  test("page_forgot_password_accessible_sans_session", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    const rep = await page.goto("/forgot-password");
    expect(rep?.status()).not.toBe(500);
    await expect(page.locator("form, .alerte")).toBeVisible();
    await ctx.close();
  });

  test("forgotPassword_soumissionNormale_afficheSucces", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/forgot-password");
    await page.fill("#email", "inconnu@inexistant.fr");
    await page.click('button[type="submit"]');
    await expect(page.locator(".alerte-succes")).toBeVisible();
    await expect(page.locator(".alerte-erreur")).not.toBeVisible();
    await ctx.close();
  });

  // Distingue un échec de la requête réseau elle-même (hors ligne, avant d'atteindre le
  // serveur) du message de succès générique — sans jamais révéler l'existence d'un compte
  // (même comportement pour un email inconnu, voir ci-dessus).
  test("forgotPassword_echecReseau_afficheErreurDistinctDuSucces", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/forgot-password");
    await page.route("**/api/auth/forgot-password", (route) => route.abort());
    await page.fill("#email", "quelconque@exemple.fr");
    await page.click('button[type="submit"]');
    await expect(page.locator(".alerte-erreur")).toBeVisible();
    await expect(page.locator(".alerte-succes")).not.toBeVisible();
    await ctx.close();
  });

  test("page_reset_password_sans_token_redirige_vers_forgot", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/reset-password");
    await expect(page).toHaveURL(/forgot-password/);
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

test.describe("Scénario: API auth", () => {
  test("reponse_http_contient_permissions_policy", async ({ request }) => {
    const rep = await request.get("/");
    const header = rep.headers()["permissions-policy"];
    expect(header).toBeDefined();
    expect(header).toContain("camera=()");
    expect(header).toContain("microphone=()");
    expect(header).toContain("geolocation=()");
    expect(header).toContain("payment=()");
  });

  test("logout_set_cookie_suppress_secure_et_samesite_lax", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    await ctx.request.post("/api/auth/login", {
      data: {
        identifiant: E2E_USER_IDENTIFIANT,
        motDePasse: E2E_USER_MOT_DE_PASSE,
      },
    });
    const rep = await ctx.request.post("/api/auth/logout");
    const setCookie = (rep.headers()["set-cookie"] ?? "").toLowerCase();
    expect(setCookie).toContain("secure");
    expect(setCookie).toContain("samesite=lax");
    await ctx.close();
  });

  for (const chemin of [
    "/login",
    "/forgot-password",
    "/reset-password",
    "/setup",
  ]) {
    test(`page_publique_${chemin}_sans_appel_authentifie_en_401`, async ({
      browser,
    }) => {
      const ctx = await browser.newContext({ storageState: undefined });
      const page = await ctx.newPage();
      const reponses401: string[] = [];
      page.on("response", (rep) => {
        if (rep.status() === 401) reponses401.push(rep.url());
      });
      await page.goto(chemin);
      // Toutes les réponses du chargement doivent avoir été observées : l'attente est le but ici.
      await page.waitForLoadState("networkidle");
      expect(reponses401).toEqual([]);
      await ctx.close();
    });
  }
});
