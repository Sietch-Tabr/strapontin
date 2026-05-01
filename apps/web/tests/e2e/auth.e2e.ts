import { test, expect } from "@playwright/test";

test.describe("Scénario: authentification et gardes de route", () => {
  test("accueil_charge_si_authentifie", async ({ page }) => {
    await page.goto("/");
    await expect(page).not.toHaveURL(/login/);
    await expect(page.locator("main")).toBeVisible();
  });

  test("route_protegee_redirige_vers_login_sans_session", async ({
    browser,
  }) => {
    // Nouveau contexte sans session
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/spectacles");
    await expect(page).toHaveURL(/login/);
    await ctx.close();
  });

  test("route_admin_accessible_avec_compte_admin", async ({ page }) => {
    await page.goto("/admin");
    await expect(page).not.toHaveURL(/login/);
    // La page admin contient l'en-tête de la liste des utilisateurs
    await expect(page.getByRole("heading", { name: /Utilisateurs/ })).toBeVisible();
  });

  test("page_login_accessible_sans_erreur", async ({ page }) => {
    const response = await page.goto("/login");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("form")).toBeVisible();
  });
});
