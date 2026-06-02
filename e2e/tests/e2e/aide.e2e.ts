import { test, expect } from "@playwright/test";

test.describe("Scénario: aide — contenu et formulaire de signalement", () => {
  test("aide_h1_visible", async ({ page }) => {
    await page.goto("/aide");
    await expect(
      page.getByRole("heading", { name: "Aide", level: 1 }),
    ).toBeVisible();
  });

  test("aide_section_artistes_salles_visible", async ({ page }) => {
    await page.goto("/aide");
    await expect(
      page.getByRole("heading", { name: /Artistes/i }),
    ).toBeVisible();
  });

  test("aide_section_signaler_visible", async ({ page }) => {
    await page.goto("/aide");
    await expect(
      page.getByRole("heading", { name: /Signaler/i }),
    ).toBeVisible();
  });

  test("aide_formulaire_signalement_present", async ({ page }) => {
    await page.goto("/aide");
    const btn = page.locator("#btn-ouvrir-signalement");
    if (!(await btn.isVisible())) {
      // Resend non configuré : vérifier le lien mailto à la place
      await expect(page.locator("a[href^='mailto:']")).toBeVisible();
      return;
    }
    await btn.click();
    await expect(page.locator("#form-signalement")).toBeVisible();
  });

  test("aide_lien_changelog_present", async ({ page }) => {
    await page.goto("/aide");
    const lienChangelog = page.locator("a[href='/changelog']");
    await expect(lienChangelog.first()).toBeVisible();
  });

  test("changelog_contenu_visible", async ({ page }) => {
    await page.goto("/changelog");
    await expect(page.locator("main")).toBeVisible();
    await expect(page.locator("main")).not.toBeEmpty();
  });
});
