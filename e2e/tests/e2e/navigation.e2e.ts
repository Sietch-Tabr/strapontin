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
    const rep = await request.get("/actuator/health");
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
        await new Promise<void>((r) => setTimeout(r, 400));
        await route.continue();
      });

      const gotoPromise = page.goto(url);
      await expect(page.locator(".etat-chargement")).toBeVisible({
        timeout: 2000,
      });
      await gotoPromise;
      await expect(page.locator(".etat-chargement")).not.toBeVisible({
        timeout: 3000,
      });
    });
  }
});

test.describe("Scénario: indicateur de chargement admin (#644)", () => {
  test.use({ storageState: FICHIER_AUTH_ADMIN });

  test("admin_jobs_etat_chargement_visible_pendant_fetch_puis_absent", async ({
    page,
  }) => {
    await page.route("**/api/admin/jobs**", async (route) => {
      await new Promise<void>((r) => setTimeout(r, 400));
      await route.continue();
    });

    const gotoPromise = page.goto("/admin");
    await expect(page.locator(".etat-chargement").first()).toBeVisible({
      timeout: 2000,
    });
    await gotoPromise;
    await expect(page.locator(".etat-chargement").first()).not.toBeVisible({
      timeout: 3000,
    });
  });
});
