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

  test("aide_lien_changelog_present", async ({ page, isMobile }) => {
    await page.goto("/aide");
    if (isMobile) {
      // Sur mobile, /changelog est dans le drawer — vérifier qu'il y est
      await page.click("#btn-nav-plus");
      await expect(
        page.locator("#dialog-nav-plus a[href='/changelog']"),
      ).toBeVisible();
      await page.keyboard.press("Escape");
    } else {
      await expect(page.locator(".sidebar a[href='/changelog']")).toBeVisible();
    }
  });

  test("changelog_contenu_visible", async ({ page }) => {
    await page.goto("/changelog");
    await expect(page.locator("main")).toBeVisible();
    await expect(page.locator("main")).not.toBeEmpty();
  });

  // #775 : garde-fou contre la récidive — sans repli automatique, chaque nouvelle version
  // laissée en <section> non repliée fait grossir la page indéfiniment (12 versions dépliées
  // d'affilée constatées avant ce correctif). Échoue si la convention documentée dans
  // ChangelogPage.vue (convertir la <section> précédente en <details> à chaque nouvelle version)
  // n'est pas respectée.
  test("changelog_versionsAnciennes_repliees", async ({ page }) => {
    await page.goto("/changelog");
    const sectionsOuvertes = await page.locator(".changelog > section").count();
    expect(sectionsOuvertes).toBeLessThanOrEqual(2);
  });

  test("aide_suppression_compte_lien_self_service_present", async ({
    page,
  }) => {
    await page.goto("/aide");
    await expect(page.locator("body")).not.toContainText(
      "sur demande à l'administrateur",
    );
    await expect(
      page.getByRole("link", { name: "Paramètres → Compte" }),
    ).toBeVisible();
  });
});
