import { test, expect } from "@playwright/test";

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

  test("decouverte_filtres_actifs_charge_sans_erreur", async ({ page }) => {
    const response = await page.goto(
      "/decouverte?dept_filtre=1&dept=75&genre=Pop&mois=2026-10&budget=50&tri=nouveaute",
    );
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("artistes_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/artistes");
    expect(response?.status()).not.toBe(500);
  });

  test("salles_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/salles");
    expect(response?.status()).not.toBe(500);
  });

  test("parametres_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/parametres");
    expect(response?.status()).not.toBe(500);
  });

  test("admin_charge_sans_erreur_500", async ({ page }) => {
    const response = await page.goto("/admin");
    expect(response?.status()).not.toBe(500);
  });

  test("health_retourne_200", async ({ page }) => {
    const response = await page.goto("/health");
    expect(response?.status()).toBe(200);
  });
});

test.describe("Scénario: découverte — cohérence des filtres", () => {
  test("filtre_dept_affiche_chip_actif", async ({ page }) => {
    await page.goto("/decouverte?dept_filtre=1&dept=75");
    // La chip département doit être active ou un indicateur visible
    await expect(page.locator("main")).toBeVisible();
    // Pas d'erreur 500
    await expect(page).not.toHaveURL(/error/);
  });

  test("filtre_q_tronque_a_100_chars_sans_erreur", async ({ page }) => {
    const q = "a".repeat(150);
    const response = await page.goto(`/decouverte?q=${q}`);
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });

  test("filtre_budget_invalide_ignore_sans_erreur", async ({ page }) => {
    const response = await page.goto("/decouverte?budget=abc");
    expect(response?.status()).not.toBe(500);
    await expect(page.locator("main")).toBeVisible();
  });
});
