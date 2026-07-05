import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_ADMIN, FICHIER_AUTH_USER } from "./global-setup.js";

test.describe("Scénario: navigation et chargement des pages", () => {
  test("accueil_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("spectacles_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/spectacles");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("decouverte_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/decouverte");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("artistes_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/artistes");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("salles_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/salles");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("parametres_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/parametres");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("aide_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/aide");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("changelog_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/changelog");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("health_actuator_retourne_status_up", async ({ request }) => {
    const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";
    const managementUrl = BASE_URL.replace(/:8080$/, ":9090").replace(
      /:3000$/,
      ":9090",
    );
    const rep = await request.get(`${managementUrl}/actuator/health`);
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(corps.status).toBe("UP");
  });
});

test.describe("Scénario: navigation admin", () => {
  test.use({ storageState: FICHIER_AUTH_ADMIN });

  test("admin_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/admin");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });
});

test.describe("Scénario: découverte — robustesse des filtres", () => {
  test("filtres_complets_charge_sans_erreur", async ({ page }) => {
    const response = await page.goto(
      "/decouverte?dept=75&genre=Pop&mois=2026-10&budget=50&tri=nouveaute",
    );
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("filtre_q_long_sans_erreur", async ({ page }) => {
    const q = "a".repeat(150);
    const response = await page.goto(`/decouverte?q=${encodeURIComponent(q)}`);
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("filtre_budget_invalide_ignore_sans_erreur", async ({ page }) => {
    const response = await page.goto("/decouverte?budget=abc");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("filtre_tri_invalide_ignore_sans_erreur", async ({ page }) => {
    const response = await page.goto("/decouverte?tri=valeur_inconnue");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("filtre_mois_format_invalide_sans_erreur", async ({ page }) => {
    const response = await page.goto("/decouverte?mois=pas-une-date");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  // #819 : "pas-une-date" ci-dessus ne matche même pas la regex de forme — ne reproduit donc
  // jamais le crash réel. "2026-13" passe la regex (4 chiffres-tiret-2 chiffres) mais n'est pas
  // un mois calendaire valide, faisant échouer LocalDate.parse avec une exception non interceptée
  // avant correctif (500 générique au lieu du filtre ignoré documenté).
  test("filtre_mois_calendaire_invalide_sans_erreur", async ({ page }) => {
    const response = await page.goto("/decouverte?mois=2026-13");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });
});

test.describe("Scénario: spectacles — robustesse des filtres", () => {
  test("spectacles_chip_indisponibles_sans_erreur", async ({ page }) => {
    const response = await page.goto("/spectacles?indisponibles=1");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("spectacles_chip_masques_sans_erreur", async ({ page }) => {
    const response = await page.goto("/spectacles?masques=1");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("spectacles_chip_quinzaine_sans_erreur", async ({ page }) => {
    const response = await page.goto("/spectacles?quinzaine=1");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("spectacles_tri_nouveaute_sans_erreur", async ({ page }) => {
    const response = await page.goto("/spectacles?tri=nouveaute");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  // #819 : équivalent manquant pour /spectacles — FiltreConcertsBuilder.appliquerMois est
  // partagé entre SpectacleService et DecouverteService, le crash affectait les deux routes.
  test("spectacles_mois_calendaire_invalide_sans_erreur", async ({ page }) => {
    const response = await page.goto("/spectacles?mois=2026-13");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });
});

test.describe("Scénario: navigation Vue Router sans rechargement complet", () => {
  test("bottomnav_decouverte_vuerouter_sans_rechargement_document", async ({
    page,
    isMobile,
  }) => {
    await page.goto("/spectacles");
    await page.evaluate(() => {
      (window as any).__testMarqueur = 1;
    });

    let navigationDocument = false;
    page.on("request", (req) => {
      if (
        req.resourceType() === "document" &&
        new URL(req.url()).pathname === "/decouverte"
      ) {
        navigationDocument = true;
      }
    });

    if (isMobile) {
      await page.locator("#bottom-nav a[href='/decouverte']").click();
    } else {
      await page.locator(".sidebar a[href='/decouverte']").click();
    }

    await expect(page.locator("main#contenu-principal")).toBeVisible();
    expect(navigationDocument).toBe(false);
    const marqueur = await page.evaluate(() => (window as any).__testMarqueur);
    expect(marqueur).toBe(1);
  });

  test("drawer_artistes_vuerouter_sans_rechargement_document", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();

    await page.goto("/spectacles");
    await page.evaluate(() => {
      (window as any).__testMarqueur = 1;
    });

    let navigationDocument = false;
    page.on("request", (req) => {
      if (
        req.resourceType() === "document" &&
        new URL(req.url()).pathname === "/artistes"
      ) {
        navigationDocument = true;
      }
    });

    await page.locator("#btn-nav-plus").click();
    await expect(page.locator("#dialog-nav-plus")).toBeVisible();
    await page.locator("#dialog-nav-plus a[href='/artistes']").click();

    await expect(page.locator("main#contenu-principal")).toBeVisible();
    expect(navigationDocument).toBe(false);
    const marqueur = await page.evaluate(() => (window as any).__testMarqueur);
    expect(marqueur).toBe(1);
  });
});

test.describe("Scénario: indicateur de chargement (#644)", () => {
  test.use({ storageState: FICHIER_AUTH_USER });

  for (const { nom, url, api } of [
    { nom: "artistes", url: "/artistes", api: "/api/artistes" },
    { nom: "salles", url: "/salles", api: "/api/salles" },
    { nom: "spectacles", url: "/spectacles", api: "/api/spectacles" },
    { nom: "decouverte", url: "/decouverte", api: "/api/decouverte" },
  ]) {
    test(`${nom}_etat_chargement_visible_pendant_fetch_puis_absent`, async ({
      page,
    }) => {
      await page.route(`**${api}**`, async (route) => {
        await new Promise<void>((r) => setTimeout(r, 1500));
        await route.continue();
      });

      const gotoPromise = page.goto(url);
      // Ne pas attendre domcontentloaded : en Firefox, cet événement peut se déclencher
      // après que Vue a déjà monté et traité la réponse retardée, ce qui fait manquer l'état.
      await expect(page.locator(".etat-chargement")).toBeVisible({
        timeout: 5000,
      });
      await gotoPromise;
      await expect(page.locator(".etat-chargement")).not.toBeVisible({
        timeout: 5000,
      });
    });
  }
});

test.describe("Scénario: indicateur de chargement admin (#644)", () => {
  test.use({ storageState: FICHIER_AUTH_ADMIN });

  test("admin_jobs_etat_chargement_visible_pendant_fetch_puis_absent", async ({
    page,
  }) => {
    await page.route("**/api/admin/**", async (route) => {
      await new Promise<void>((r) => setTimeout(r, 1500));
      await route.continue();
    });

    const gotoPromise = page.goto("/admin");
    // Ne pas attendre domcontentloaded (race condition Firefox — voir tests pages ci-dessus).
    await expect(page.locator(".etat-chargement").first()).toBeVisible({
      timeout: 5000,
    });
    await gotoPromise;
    await expect(page.locator(".etat-chargement").first()).not.toBeVisible({
      timeout: 5000,
    });
  });
});

// #823 : useSession.js traitait une exception réseau générique (fetch échoué) exactement comme
// un 401 confirmé — session.value passait à null dans les deux cas, ce qui redirigeait vers
// /login au prochain re-appel de chargerSession() (ex. écouteur 'online') alors que le cookie de
// session restait valide côté serveur. Après correctif, seul un 401 confirmé invalide la session.
test.describe("Scénario: garde de session — coupure réseau transitoire (#823)", () => {
  test.use({ storageState: FICHIER_AUTH_USER });

  test("erreurReseauSurChargerSession_neRedirigePasVersLogin", async ({
    page,
  }) => {
    // Attendre la réponse du premier appel /api/session (déclenché par App.vue onMounted) avant
    // de dispatcher 'online' — le listener 'online' n'est attaché qu'une fois ce premier appel
    // résolu côté JS.
    await Promise.all([
      page.waitForResponse((r) => r.url().includes("/api/session")),
      page.goto("/"),
    ]);
    await expect(page.locator("main")).toBeVisible();

    let intercepte = false;
    await page.route("**/api/session", async (route) => {
      intercepte = true;
      await route.abort();
    });

    // Le listener 'online' de App.vue rappelle chargerSession() — simule une coupure réseau
    // brève suivie d'un retour de connectivité pendant que /api/session est encore instable.
    // #823 : chargerSession() ignore tout appel concurrent tant qu'un premier est en cours
    // (garde chargement.value) — un dispatch immédiatement après waitForResponse ci-dessus peut
    // encore tomber pendant la fenêtre où ce guard est actif (la réponse réseau et l'exécution du
    // finally() côté JS ne sont pas synchronisées), rendant un dispatch unique flaky. Redispatcher
    // en boucle jusqu'à interception plutôt que de deviner le bon délai.
    await expect
      .poll(
        async () => {
          await page.evaluate(() => window.dispatchEvent(new Event("online")));
          return intercepte;
        },
        { timeout: 5000 },
      )
      .toBe(true);

    // Laisse le catch/finally de chargerSession() s'exécuter avant de vérifier l'absence de
    // redirection.
    await page.waitForTimeout(300);
    await expect(page).not.toHaveURL(/\/login/);
    await expect(page.locator("main")).toBeVisible();
  });
});
