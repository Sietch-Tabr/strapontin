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

    // Délai réel du filet de secours : 4000ms (offline-fallback.js) — marge portée à 10s (au
    // lieu de 6s) pour absorber la contention CPU/réseau sous charge parallèle complète de la
    // suite (jalon 2.6.4, volume de tests fortement accru), sans changer le comportement vérifié.
    await expect(
      page.getByRole("heading", { name: "Vous êtes hors ligne" }),
    ).toBeVisible({ timeout: 10000 });
    await expect(page.getByRole("button", { name: "Réessayer" })).toBeVisible();
  });
});

// #841 : distinct du scénario ci-dessus (bundle JS inatteignable) — ici le JS charge et Vue
// monte normalement, seule la connectivité réseau est absente au démarrage. Les listeners
// offline/online de App.vue ne réagissent qu'aux transitions d'état ; navigator.onLine n'était
// vérifié nulle part au montage, donc le toast persistant n'apparaissait jamais si la PWA
// démarrait déjà hors-ligne (ex. depuis le cache du service worker).
test.describe("Scénario: PWA — démarrage alors que l'appareil est déjà hors-ligne (#841)", () => {
  test("pwa_demarreDejaHorsLigne_afficheToastPersistantImmediatement", async ({
    page,
  }) => {
    await page.addInitScript(() => {
      Object.defineProperty(window.navigator, "onLine", {
        get: () => false,
        configurable: true,
      });
    });

    await page.goto("/");
    await expect(page.locator("main")).toBeVisible();
    await expect(
      page
        .locator(".toast-conteneur .alerte-erreur")
        .filter({ hasText: "Vous êtes hors ligne" }),
    ).toBeVisible({ timeout: 3000 });
  });
});

// #1027 : le toast (offline persistant ou toute autre alerte) restait masqué derrière la bottom
// nav sur mobile — le sélecteur de la media query mobile de components.css ne ciblait que
// [popover].alerte, jamais matché par le composant réel (.toast-conteneur .alerte).
test.describe("Scénario: PWA — toast visible au-dessus de la bottom nav sur mobile (#1027)", () => {
  test("pwa_toastHorsLigneMobile_positionneAuDessusDeLaBottomNav", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();

    await page.addInitScript(() => {
      Object.defineProperty(window.navigator, "onLine", {
        get: () => false,
        configurable: true,
      });
    });

    await page.goto("/");
    const toast = page
      .locator(".toast-conteneur .alerte")
      .filter({ hasText: "Vous êtes hors ligne" });
    await expect(toast).toBeVisible({ timeout: 3000 });
    const bottomNav = page.locator("#bottom-nav");
    await expect(bottomNav).toBeVisible();

    const toastBox = await toast.boundingBox();
    const bottomNavBox = await bottomNav.boundingBox();
    expect(toastBox).not.toBeNull();
    expect(bottomNavBox).not.toBeNull();
    expect(toastBox!.y + toastBox!.height).toBeLessThanOrEqual(bottomNavBox!.y);
  });
});

// #983 : /offline-fallback.js tombait dans .anyRequest().authenticated() (absent de la liste
// permitAll de SecurityConfiguration), redirigeant tout visiteur non authentifié vers /login au
// lieu de servir le script — précisément le cas où ce filet de secours serait le plus utile
// (premier lancement PWA hors-ligne, avant toute connexion). Contexte de requête non authentifié
// (playwright.config.ts), sans storageState — reproduit le visiteur anonyme sans navigation
// complète (pattern déjà utilisé dans auth.e2e.ts pour /api/auth/me).
test.describe("Scénario: offline-fallback.js accessible sans authentification (#983)", () => {
  test("offlineFallback_visiteurNonAuthentifie_accessiblePubliquement", async ({
    request,
  }) => {
    const rep = await request.get("/offline-fallback.js");

    expect(rep.status()).toBe(200);
    expect(rep.headers()["content-type"]).toMatch(/javascript/);
  });
});

// #1028/#1030 : shell PWA servi depuis le cache SW alors que l'API est inatteignable (réseau
// coupé juste après un redémarrage) — /api/session ne répond jamais, indiscernable d'un 401 côté
// router sans sessionErreurReseau (#1030). Le même redémarrage déclenchait aussi un toast
// d'erreur générique sur AccueilPage.vue pour un 401 transitoire de session pas encore confirmée
// (#1028) — non reproduit ici (401 confirmé, pas un abort réseau), couvert par le fait qu'aucun
// toast n'apparaît dans ce scénario réseau-coupé non plus (AccueilPage ne lève que sur un vrai
// échec, cf. charger()).
test.describe("Scénario: PWA — navigation interne pendant un redémarrage hors-ligne (#1028, #1030)", () => {
  test("navigation_apiSessionInatteignable_neRedirigePasVersLogin", async ({
    page,
  }) => {
    await page.route("**/api/session", (route) => route.abort());

    await page.goto("/");
    await expect(page.locator("main")).toBeVisible();

    await page.locator('a[href="/spectacles"]').click();

    await page.waitForTimeout(500);
    await expect(page).not.toHaveURL(/\/login/);
    await expect(
      page
        .locator(".toast-conteneur .alerte")
        .filter({ hasText: "Impossible de charger les concerts" }),
    ).toHaveCount(0);
  });
});
