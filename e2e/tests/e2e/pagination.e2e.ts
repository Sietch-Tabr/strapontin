import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

// Pagination partagée (PaginationListe.vue) : variante publique, boutons masqués aux extrémités,
// et variante d'administration, boutons désactivés. Les préconditions de données échouent
// franchement : un test qui s'ignore en silence ne couvre rien.
test.describe("Scénario: pagination des listes", () => {
  test("decouverte_pagination_suivantPuisPrecedent_changeLaPageEtLAdresse", async ({
    page,
  }) => {
    await page.goto("/decouverte");
    const pagination = page.locator(".pagination");

    await expect(pagination).toContainText("Page 1 /");
    await expect(
      pagination.getByRole("button", { name: "Précédent" }),
    ).toHaveCount(0);

    await pagination.getByRole("button", { name: "Suivant" }).click();
    await expect(pagination).toContainText("Page 2 /");
    await expect(page).toHaveURL(/page=1/);
    await expect(
      pagination.getByRole("button", { name: "Précédent" }),
    ).toBeVisible();

    await pagination.getByRole("button", { name: "Précédent" }).click();
    await expect(pagination).toContainText("Page 1 /");
    await expect(
      pagination.getByRole("button", { name: "Précédent" }),
    ).toHaveCount(0);
  });

  test("decouverte_pagination_adresseAvecPage_ouvreCettePage", async ({
    page,
  }) => {
    await page.goto("/decouverte?page=2");
    await expect(page.locator(".pagination")).toContainText("Page 3 /");
  });

  test("api_decouverte_pagination_concertsDistinctsEntreLesPages", async ({
    request,
  }) => {
    const repPage0 = await request.get("/api/decouverte?page=0");
    expect(repPage0.status()).toBe(200);
    const page0 = await repPage0.json();
    expect(page0.totalPages).toBeGreaterThan(1);

    const repPage1 = await request.get("/api/decouverte?page=1");
    expect(repPage1.status()).toBe(200);
    const page1 = await repPage1.json();
    expect(page1.page).toBe(1);

    const idsPage0 = new Set(page0.concerts.map((c: { id: string }) => c.id));
    expect(page1.concerts.some((c: { id: string }) => idsPage0.has(c.id))).toBe(
      false,
    );
  });

  test.describe("administration", () => {
    test.use({ storageState: FICHIER_AUTH_ADMIN });

    test("admin_referentielSalles_pagination_precedentDesactiveSurLaPremierePage", async ({
      page,
      isMobile,
    }) => {
      await page.goto("/admin");
      if (isMobile) {
        await page.selectOption("select.param-onglets-mobile", "#referentiel");
      } else {
        await page.locator('a[href="#referentiel"]').click();
      }
      await page
        .getByRole("group", { name: "Référentiel affiché" })
        .getByRole("button", { name: "Salles" })
        .click();

      const pagination = page.locator("section#salles .pagination");
      const precedent = pagination.getByRole("button", { name: /Précédent/ });
      const suivant = pagination.getByRole("button", { name: /Suivant/ });

      await expect(pagination).toContainText("Page 1 /");
      await expect(pagination).toContainText("salles)");
      await expect(precedent).toBeDisabled();

      await suivant.click();
      await expect(pagination).toContainText("Page 2 /");
      await expect(precedent).toBeEnabled();

      await precedent.click();
      await expect(pagination).toContainText("Page 1 /");
      await expect(precedent).toBeDisabled();
    });
  });
});
