import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "./global-setup.js";

async function soumettreDepartements(page: import("@playwright/test").Page) {
  await page.goto("/parametres#preferences");
  const input = page.locator('input[name="departements"]');
  await input.waitFor({ state: "visible" });
  await input.fill((await input.inputValue()) || "75");
  await page.click(
    'form:has(input[name="departements"]) button[type="submit"]',
  );
  await page
    .locator(".alerte-succes")
    .first()
    .waitFor({ state: "visible", timeout: 5000 });
}

test.describe("Toasts (Popover API)", () => {
  test("toast succes apparaît après enregistrement paramètres département", async ({
    page,
  }) => {
    await soumettreDepartements(page);
    const toast = page.locator(".alerte-succes");
    await expect(toast.first()).toBeVisible({ timeout: 3000 });
    await expect(toast.first()).toContainText("Départements enregistrés");
  });

  test("toast contient le bouton fermer", async ({ page }) => {
    await soumettreDepartements(page);

    const hasBouton = await page.waitForFunction(
      () => !!document.querySelector(".toast-fermer"),
      { timeout: 5000 },
    );
    expect(await hasBouton.jsonValue()).toBe(true);

    const estOuvert = await page.evaluate(() => {
      const el = document.querySelector(".alerte-succes");
      return !!el && el.matches(":popover-open");
    });

    if (estOuvert) {
      await page.locator(".toast-fermer").first().click();
      await page.waitForFunction(
        () => {
          const el = document.querySelector(".alerte-succes");
          return !el || !el.matches(":popover-open");
        },
        { timeout: 2000 },
      );
    }
  });

  test("toast auto-dismiss après 4 s", async ({ page }) => {
    await soumettreDepartements(page);
    await expect(page.locator(".alerte-succes").first()).toBeHidden({
      timeout: 8000,
    });
  });
});

test.describe("Toasts — actions admin", () => {
  test.use({ storageState: FICHIER_AUTH_ADMIN });

  test("toast succes admin relancer fetches", async ({ page }) => {
    await page.goto("/admin#jobs");
    await expect(page.locator(".etat-chargement").first()).not.toBeVisible({
      timeout: 5000,
    });
    const btn = page.getByRole("button", {
      name: "Relancer les fetches maintenant",
    });
    await btn.scrollIntoViewIfNeeded();
    await btn.click();
    await expect(page.locator(".alerte-succes")).toContainText(
      "Fetches relancés",
      { timeout: 5000 },
    );
  });
});
