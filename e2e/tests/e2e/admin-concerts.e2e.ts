import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

test.use({ storageState: FICHIER_AUTH_ADMIN });

test.describe("Scénario: admin — onglet À traiter", () => {
  test("admin_desktop_section_concerts_visible", async ({ page, isMobile }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#a-traiter"]').click();
    await expect(page.locator("section#concerts")).toBeVisible();
    await expect(page.locator("section#signalements")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_mobile_select_navigation_concerts", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/admin");
    await page.selectOption("select.param-onglets-mobile", "#a-traiter");
    await expect(page.locator("section#concerts")).toBeVisible();
    await expect(page.locator("section#signalements")).toBeVisible();
    await expect(page.locator("section#jobs")).not.toBeVisible();
  });

  test("admin_concerts_table_ou_etatVide_visible", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/admin");
    await page.locator('a[href="#a-traiter"]').click();
    await expect(page.locator("section#concerts")).toBeVisible();
    await expect(
      page.locator(
        "section#concerts table, section#concerts p.etat-vide-texte",
      ),
    ).toBeVisible();
  });

  test("admin_anomalies_listeOuEtatVide_visible", async ({ page }) => {
    await page.goto("/admin#a-traiter");
    await expect(
      page
        .locator(
          "section#signalements ul.liste-signalements, section#signalements p.etat-vide-texte",
        )
        .first(),
    ).toBeVisible();
  });

  test("admin_ancreHistoriqueSignalements_ouvreATraiter", async ({ page }) => {
    await page.goto("/admin#signalements");
    await expect(page).toHaveURL(/#a-traiter$/);
    await expect(page.locator("section#signalements")).toBeVisible();
  });
});

test.describe("Scénario: admin — API concerts à confirmer", () => {
  test("api_admin_concertsAConfirmer_retourne200EtTableau", async ({
    request,
  }) => {
    const rep = await request.get("/api/admin/concerts-a-confirmer");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(Array.isArray(corps.concerts)).toBe(true);
    for (const concert of corps.concerts) {
      expect(["jumeau", "annoncee_ailleurs", "introuvable"]).toContain(
        concert.situation,
      );
    }
  });

  test("api_admin_concertsAConfirmer_confirmer_uuidInvalide_retourne400", async ({
    request,
  }) => {
    const rep = await request.post(
      "/api/admin/concerts-a-confirmer/pas-un-uuid/confirmer",
    );
    expect(rep.status()).toBe(400);
  });

  test("api_admin_concertsAConfirmer_confirmer_concertInconnu_retourne404", async ({
    request,
  }) => {
    const rep = await request.post(
      "/api/admin/concerts-a-confirmer/00000000-0000-0000-0000-000000000099/confirmer",
    );
    expect(rep.status()).toBe(404);
  });

  test("api_admin_concertsAConfirmer_annuler_uuidInvalide_retourne400", async ({
    request,
  }) => {
    const rep = await request.post(
      "/api/admin/concerts-a-confirmer/pas-un-uuid/annuler",
    );
    expect(rep.status()).toBe(400);
  });

  test("api_admin_concertsAConfirmer_annuler_concertInconnu_retourne404", async ({
    request,
  }) => {
    const rep = await request.post(
      "/api/admin/concerts-a-confirmer/00000000-0000-0000-0000-000000000099/annuler",
    );
    expect(rep.status()).toBe(404);
  });
});
