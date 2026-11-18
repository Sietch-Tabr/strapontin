import { test, expect, type Page } from "@playwright/test";

// Dernières données connues quand la connexion est absente ou lente. Le service worker n'est
// pas actif en HTTP sur une IP privée : les scénarios coupent le réseau après un premier chargement
// et naviguent dans l'application déjà chargée, ce qui exerce la couche de données gardées
// (localStorage) dans les cinq navigateurs, WebKit compris.

// Le lien s'appelle « Mes spectacles » sur ordinateur et « Spectacles » dans la barre mobile.
const LIEN_SPECTACLES = /^(Mes )?spectacles$/i;
const PREFIXE_CACHE = "strapontin:cache:";

async function attendreFinDeChargement(page: Page) {
  await expect(page.locator(".etat-chargement")).toHaveCount(0, {
    timeout: 15000,
  });
}

function nbEntreesGardees(page: Page) {
  return page.evaluate(
    (prefixe) =>
      Object.keys(localStorage).filter((c) => c.startsWith(prefixe)).length,
    PREFIXE_CACHE,
  );
}

async function visiterEnLigne(page: Page, chemin: string) {
  await page.goto(chemin);
  await expect(page.locator("main")).toBeVisible();
  await attendreFinDeChargement(page);
}

async function ouvrirLien(page: Page, nom: string | RegExp) {
  await page.getByRole("link", { name: nom }).first().click();
  await attendreFinDeChargement(page);
}

// Charge l'accueil puis Mes spectacles par navigation interne : les deux composants de page sont
// en mémoire (aucun chargement de module à faire une fois le réseau coupé) et les deux listes
// sont gardées. On revient sur l'accueil, d'où l'on rouvrira Mes spectacles hors ligne.
async function preparerDeuxPagesGardees(page: Page) {
  await visiterEnLigne(page, "/");
  await ouvrirLien(page, LIEN_SPECTACLES);
  await expect(page.getByText(/\d+ concerts? à venir/)).toBeVisible();
  await ouvrirLien(page, "Accueil");
  expect(await nbEntreesGardees(page)).toBeGreaterThanOrEqual(2);
}

test.describe("Scénario: données gardées — connexion absente", () => {
  test("horsLigne_apresUneVisite_afficheLesDernieresDonneesEtLeBandeau", async ({
    page,
    context,
  }) => {
    await preparerDeuxPagesGardees(page);

    await context.setOffline(true);
    await page.getByRole("link", { name: LIEN_SPECTACLES }).first().click();

    await expect(
      page.getByText("Connexion absente ou trop lente", { exact: false }),
    ).toBeVisible({ timeout: 10000 });
    // Le décompte n'existe que si la liste a été chargée : elle vient bien des données gardées.
    await expect(page.getByText(/\d+ concerts? à venir/)).toBeVisible();
    await expect(page.getByText("Impossible de charger")).toHaveCount(0);
    await context.setOffline(false);
  });

  test("horsLigne_retourDeLaConnexion_remplaceLesDonneesGardeesSansAction", async ({
    page,
    context,
  }) => {
    await preparerDeuxPagesGardees(page);
    await context.setOffline(true);
    await page.getByRole("link", { name: LIEN_SPECTACLES }).first().click();
    const bandeau = page.getByText("Connexion absente ou trop lente", {
      exact: false,
    });
    await expect(bandeau).toBeVisible({ timeout: 10000 });

    // Le bandeau apparaît avant la fin de la navigation : on attend la page visée, sinon le
    // retour du réseau interrompt la navigation et la page rechargée reste sur l'accueil.
    await expect(page).toHaveURL(/\/spectacles$/);
    await expect(page.getByText(/\d+ concerts? à venir/)).toBeVisible();
    await context.setOffline(false);

    // Retour du réseau : la page se recharge d'elle-même (événement online ou sonde de 15 s).
    await expect(bandeau).toHaveCount(0, { timeout: 30000 });
    await expect(page.getByText(/\d+ concerts? à venir/)).toBeVisible();
  });

  test("horsLigne_sansAucuneCopie_afficheTouteDeSuiteLEcranHorsLigne", async ({
    page,
    context,
  }) => {
    await preparerDeuxPagesGardees(page);
    await page.evaluate((prefixe) => {
      for (const c of Object.keys(localStorage)) {
        if (c.startsWith(prefixe)) localStorage.removeItem(c);
      }
    }, PREFIXE_CACHE);

    await context.setOffline(true);
    await page.getByRole("link", { name: LIEN_SPECTACLES }).first().click();

    // Aucune copie : l'écran hors ligne remplace la page, sans toast d'erreur de chargement ni
    // bandeau annonçant des données gardées qui n'existent pas.
    await expect(
      page.getByRole("heading", { name: "Vous êtes hors ligne" }),
    ).toBeVisible({ timeout: 5000 });
    await expect(page.getByText("Impossible de")).toHaveCount(0);
    await expect(
      page.getByText("Connexion absente ou trop lente", { exact: false }),
    ).toHaveCount(0);
    await context.setOffline(false);
  });

  test("horsLigne_sansAucuneCopie_retourDeLaConnexion_rechargeSansAction", async ({
    page,
    context,
  }) => {
    await preparerDeuxPagesGardees(page);
    await page.evaluate((prefixe) => {
      for (const c of Object.keys(localStorage)) {
        if (c.startsWith(prefixe)) localStorage.removeItem(c);
      }
    }, PREFIXE_CACHE);
    await context.setOffline(true);
    await page.getByRole("link", { name: LIEN_SPECTACLES }).first().click();
    const ecran = page.getByRole("heading", { name: "Vous êtes hors ligne" });
    await expect(ecran).toBeVisible({ timeout: 5000 });

    await context.setOffline(false);

    await expect(ecran).toHaveCount(0, { timeout: 30000 });
    await expect(page.locator("main")).toBeVisible();
  });
});

test.describe("Scénario: données gardées — connexion lente", () => {
  test("connexionLente_sansCopie_signaleLaLenteurAuLieuDUnePageMuette", async ({
    page,
  }) => {
    await page.route("**/api/spectacles**", () => {
      // Ne répond jamais : serveur joignable mais sans réponse.
    });

    await page.goto("/spectacles", { waitUntil: "domcontentloaded" });

    await expect(
      page.getByText("Connexion lente", { exact: false }),
    ).toBeVisible({ timeout: 8000 });
  });

  test("connexionLente_avecCopie_basculeSurLesDonneesGardees", async ({
    page,
  }) => {
    await visiterEnLigne(page, "/spectacles");
    await page.route("**/api/spectacles**", () => {
      // Ne répond jamais.
    });

    await page.reload({ waitUntil: "domcontentloaded" });

    await expect(
      page.getByText("Connexion absente ou trop lente", { exact: false }),
    ).toBeVisible({ timeout: 12000 });
    await expect(page.getByText(/\d+ concerts? à venir/)).toBeVisible();
  });
});

test.describe("Scénario: appels d'arrière-plan", () => {
  test("arrierePlan_banniereLente_neSignalePasDeLenteur", async ({ page }) => {
    await page.route("**/api/banniere/json", async (route) => {
      await new Promise((resoudre) => setTimeout(resoudre, 6000));
      await route.continue();
    });

    await page.goto("/", { waitUntil: "domcontentloaded" });
    await expect(page.locator("main")).toBeVisible();
    await page.waitForTimeout(7000);

    await expect(
      page.getByText("Connexion lente", { exact: false }),
    ).toHaveCount(0);
  });
});

test.describe("Scénario: données gardées — effacement", () => {
  // Un autre compte sur le même appareil passe par la page de connexion : rien du précédent
  // ne doit rester lisible.
  test.use({ storageState: { cookies: [], origins: [] } });

  test("pageDeConnexion_effaceLesDonneesGardeesDUnPrecedentCompte", async ({
    page,
  }) => {
    await page.addInitScript((prefixe) => {
      localStorage.setItem(
        `${prefixe}/api/accueil`,
        JSON.stringify({ corps: "{}", enregistreLe: Date.now() }),
      );
      localStorage.setItem("theme", "sombre");
    }, PREFIXE_CACHE);

    await page.goto("/login");
    await expect(page.locator("main, #app")).toBeVisible();

    await expect.poll(() => nbEntreesGardees(page)).toBe(0);
    // Seules les données gardées sont effacées, pas les préférences de l'appareil.
    expect(await page.evaluate(() => localStorage.getItem("theme"))).toBe(
      "sombre",
    );
  });
});
