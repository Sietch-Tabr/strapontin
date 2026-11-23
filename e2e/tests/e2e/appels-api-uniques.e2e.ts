import { test, expect, type Page } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

const POINTS_D_ACCES = [
  "/api/session",
  "/api/banniere/json",
  "/api/user/config/canal-perdu",
];

// Compte les appels de chaque point d'accès surveillé émis par la page.
function surveillerAppels(page: Page): Map<string, number> {
  const appels = new Map<string, number>(POINTS_D_ACCES.map((p) => [p, 0]));
  page.on("request", (requete) => {
    const chemin = new URL(requete.url()).pathname;
    if (appels.has(chemin)) appels.set(chemin, (appels.get(chemin) ?? 0) + 1);
  });
  return appels;
}

async function attendreFinDesChargements(page: Page) {
  await expect(page.locator("main")).toBeVisible();
  await page.waitForLoadState("networkidle");
}

function verifierUnSeulAppel(appels: Map<string, number>, absents: string[]) {
  for (const point of POINTS_D_ACCES) {
    if (absents.includes(point)) continue;
    expect(appels.get(point), `appels de ${point}`).toBe(1);
  }
}

test.describe("Scénario: un seul appel par point d'accès au chargement complet", () => {
  for (const chemin of [
    "/",
    "/spectacles",
    "/decouverte",
    "/salles",
    "/artistes",
    "/parametres",
  ]) {
    test(`chargement_complet_${chemin}_appelleChaquePointDAccesUneFois`, async ({
      page,
    }) => {
      const appels = surveillerAppels(page);

      await page.goto(chemin);
      await attendreFinDesChargements(page);

      verifierUnSeulAppel(appels, []);
    });
  }

  test("changement_de_page_relitLaBanniereEtLeCanalPerdu", async ({ page }) => {
    await page.goto("/");
    await attendreFinDesChargements(page);
    const appels = surveillerAppels(page);

    await page.locator('a[href="/spectacles"]:visible').first().click();
    await expect(page).toHaveURL(/\/spectacles$/);
    await attendreFinDesChargements(page);

    expect(appels.get("/api/banniere/json")).toBe(1);
    expect(appels.get("/api/user/config/canal-perdu")).toBe(1);
  });
});

test.describe("Scénario: un seul appel par point d'accès en session administrateur", () => {
  test.use({ storageState: FICHIER_AUTH_ADMIN });

  test("chargement_complet_admin_appelleChaquePointDAccesUneFois", async ({
    page,
  }) => {
    const appels = surveillerAppels(page);

    await page.goto("/admin");
    await attendreFinDesChargements(page);

    verifierUnSeulAppel(appels, []);
  });
});
