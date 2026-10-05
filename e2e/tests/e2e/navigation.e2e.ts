import { test, expect, type Page } from "@playwright/test";
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

  // #880 : contrairement à /decouverte, aucun test ne couvrait les filtres genre/classification
  // sur /spectacles.
  test("spectacles_filtreGenre_sansErreur", async ({ page }) => {
    const response = await page.goto("/spectacles?genre=Pop");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("spectacles_filtreClassification_sansErreur", async ({ page }) => {
    const response = await page.goto("/spectacles?classification=concert");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  // #880 : le test existant spectacles_tri_nouveaute_sans_erreur ne vérifie que l'absence de
  // 500 — jamais l'ordre réel retourné. Vérifié au niveau API (contenu déterministe, pas
  // dépendant du rendu DOM ni des données réelles présentes sur l'instance de test).
  test("api_spectacles_triNouveaute_ordreParFirstSeenDecroissant", async ({
    request,
  }) => {
    const rep = await request.get("/api/spectacles?tri=nouveaute");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    const dates = (corps.concerts as { firstSeen: string | null }[])
      .map((c) => c.firstSeen)
      .filter((d): d is string => d != null)
      .map((d) => new Date(d).getTime());
    for (let i = 1; i < dates.length; i++) {
      expect(dates[i]).toBeLessThanOrEqual(dates[i - 1]);
    }
  });

  // #880 : la pagination de /spectacles n'était jamais testée.
  test("api_spectacles_pagination_pageReflewteeEtConcertsDistinctsDeLaPage0", async ({
    request,
  }) => {
    const repPage0 = await request.get("/api/spectacles?page=0");
    expect(repPage0.status()).toBe(200);
    const page0 = await repPage0.json();
    expect(page0.page).toBe(0);

    if (page0.totalPages <= 1) {
      test.skip(
        true,
        "une seule page de résultats sur cette instance — rien à comparer",
      );
      return;
    }

    const repPage1 = await request.get("/api/spectacles?page=1");
    expect(repPage1.status()).toBe(200);
    const page1 = await repPage1.json();
    expect(page1.page).toBe(1);

    const idsPage0 = new Set(page0.concerts.map((c: { id: string }) => c.id));
    const idsPage1 = page1.concerts.map((c: { id: string }) => c.id);
    expect(idsPage1.some((id: string) => idsPage0.has(id))).toBe(false);
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
      await page.locator("#nav-bas a[href='/decouverte']").click();
    } else {
      await page.locator(".barre-laterale a[href='/decouverte']").click();
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

  test("navigationInterne_depuisPageScrollee_remonteLeScrollEnHaut", async ({
    page,
    isMobile,
  }) => {
    // /changelog est la seule page dont le contenu dépasse systématiquement la hauteur de
    // viewport par défaut — nécessaire pour produire un vrai scroll à remettre à zéro.
    await page.goto("/changelog");
    await expect(page.locator("main#contenu-principal")).toBeVisible();
    // le contenu du changelog (sections repliées) se déploie après le premier rendu — attendre
    // qu'il dépasse effectivement la hauteur de viewport avant de tenter le scroll.
    await expect
      .poll(() =>
        page.evaluate(
          () => document.documentElement.scrollHeight > window.innerHeight,
        ),
      )
      .toBe(true);

    await page.evaluate(() => window.scrollTo(0, 800));
    await expect
      .poll(() => page.evaluate(() => window.scrollY))
      .toBeGreaterThan(0);

    if (isMobile) {
      await page.locator("#btn-nav-plus").click();
      await expect(page.locator("#dialog-nav-plus")).toBeVisible();
      await page.locator("#dialog-nav-plus a[href='/artistes']").click();
    } else {
      await page.locator(".barre-laterale a[href='/artistes']").click();
    }

    await expect(page.locator("main#contenu-principal")).toBeVisible();
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
  });
});

// Sous WebKit, une interception active fait passer toutes les requêtes de la page par
// Playwright, qui attribue parfois à une URL la réponse d'une autre (CSS reçu en
// text/javascript, navigation initiale jamais terminée : environ 1 fois sur 170, aucune sur
// 600 sans interception). Les tests de l'indicateur ne l'activent donc qu'une fois
// l'application et les modules de la page chargés, pour les seuls appels d'API.

// Clic sur le lien de navigation de l'application (barre latérale sur ordinateur ; barre du
// bas ou tiroir « Plus » sur mobile) : navigation du routeur, sans rechargement du document.
async function naviguerEnInterne(
  page: Page,
  chemin: string,
  isMobile: boolean,
) {
  if (!isMobile) {
    await page.locator(`.barre-laterale a[href='${chemin}']`).first().click();
  } else if ((await page.locator(`#nav-bas a[href='${chemin}']`).count()) > 0) {
    await page.locator(`#nav-bas a[href='${chemin}']`).click();
  } else {
    await page.locator("#btn-nav-plus").click();
    await page.locator(`#dialog-nav-plus a[href='${chemin}']`).click();
  }
  await expect(page).toHaveURL(new RegExp(`${chemin.replace("/", "\\/")}$`));
}

// Charge la page (modules mis en mémoire) puis la quitte vers l'accueil.
async function prechargerPuisQuitter(
  page: Page,
  url: string,
  indicateur: string,
  isMobile: boolean,
) {
  await page.goto(url);
  await expect(page.locator(indicateur).first()).not.toBeVisible({
    timeout: 10000,
  });
  await naviguerEnInterne(page, "/", isMobile);
}

// Retarde de 1,5 s les appels d'API visés ; « interception » se résout à la première d'entre
// elles (dans un objet : une promesse rendue par une fonction async serait attendue avec elle).
async function retarderApi(
  page: Page,
  motif: string,
): Promise<{ interception: Promise<void> }> {
  let interceptee: () => void = () => {};
  const interception = new Promise<void>((resolve) => {
    interceptee = resolve;
  });
  await page.route(motif, async (route) => {
    interceptee();
    await new Promise<void>((r) => setTimeout(r, 1500));
    await route.continue();
  });
  return { interception };
}

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
      isMobile,
    }) => {
      await prechargerPuisQuitter(page, url, ".etat-chargement", isMobile);
      const { interception } = await retarderApi(page, `**${api}**`);

      await naviguerEnInterne(page, url, isMobile);
      await interception;
      await expect(page.locator(".etat-chargement")).toBeVisible({
        timeout: 5000,
      });
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
    isMobile,
  }) => {
    await prechargerPuisQuitter(page, "/admin", ".etat-chargement", isMobile);
    const { interception } = await retarderApi(page, "**/api/admin/**");

    await naviguerEnInterne(page, "/admin", isMobile);
    await interception;
    await expect(page.locator(".etat-chargement").first()).toBeVisible({
      timeout: 5000,
    });
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

  // #880 : le pendant du test ci-dessus manquait — un 401 réellement confirmé par le serveur
  // (distinct d'un échec réseau) doit, lui, rediriger vers /login. Sans ce test symétrique, un
  // futur correctif pourrait supprimer la redirection 401 par erreur sans qu'aucun test n'échoue.
  test("session401Confirme_rediriegeVersLogin", async ({ page }) => {
    await page.route("**/api/session", (route) =>
      route.fulfill({
        status: 401,
        contentType: "application/json",
        body: "{}",
      }),
    );

    await page.goto("/");

    await expect(page).toHaveURL(/\/login/, { timeout: 5000 });
  });
});

test.describe("Scénario: Découverte — actualisation réservée aux admins", () => {
  test.describe("utilisateur", () => {
    test.use({ storageState: FICHIER_AUTH_USER });

    test("decouverte_utilisateur_boutonActualiserAbsent", async ({ page }) => {
      await page.goto("/decouverte");
      await expect(page.locator("main")).toBeVisible();
      await expect(
        page.getByRole("button", { name: "Actualiser" }),
      ).toHaveCount(0);
    });
  });

  test.describe("admin", () => {
    test.use({ storageState: FICHIER_AUTH_ADMIN });

    test("decouverte_admin_boutonActualiserVisible", async ({ page }) => {
      await page.goto("/decouverte");
      await expect(
        page.getByRole("button", { name: "Actualiser" }),
      ).toBeVisible();
    });
  });
});
