import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_USER } from "./global-setup.js";

test.describe("Scénario: accessibilité WCAG #741 — page connexion", () => {
  test("login_h1_present_pour_lecteurs_ecran", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/login");
    const h1 = page.locator("h1");
    await expect(h1).toHaveCount(1);
    await expect(h1).toContainText("Connexion");
    await ctx.close();
  });

  test("login_erreur_a_role_alert_et_aria_live_assertive", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    // Le query param totp=trop_tentatives initialise erreurSoumission sans appel API,
    // ce qui évite de consommer le rate-limiter bucket4j.
    await page.goto("/login?totp=trop_tentatives");
    const alerte = page.locator("[role='alert']");
    await expect(alerte).toBeVisible({ timeout: 3000 });
    await expect(alerte).toHaveAttribute("aria-live", "assertive");
    await ctx.close();
  });
});

test.describe("Scénario: accessibilité WCAG #741 — lien d'évitement", () => {
  test.use({ storageState: FICHIER_AUTH_USER });

  test("lien_evitement_present_et_ancre_correcte", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("main")).toBeVisible();
    const lien = page.locator(".lien-evitement");
    await expect(lien).toBeAttached();
    await expect(lien).toHaveAttribute("href", "#contenu-principal");
  });
});

test.describe("Scénario: accessibilité WCAG #742 — aria-current navigation", () => {
  test.use({ storageState: FICHIER_AUTH_USER });

  test("sidebar_aria_current_page_sur_lien_actif", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/spectacles");
    await expect(page.locator("main")).toBeVisible();
    await expect(
      page.locator(".barre-laterale-nav a[href='/spectacles']"),
    ).toHaveAttribute("aria-current", "page");
    await expect(
      page.locator(".barre-laterale-nav a[href='/']"),
    ).not.toHaveAttribute("aria-current");
  });

  test("bottom_nav_aria_current_page_sur_lien_actif", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/spectacles");
    await expect(page.locator("main")).toBeVisible();
    await expect(
      page.locator("#nav-bas a[href='/spectacles']"),
    ).toHaveAttribute("aria-current", "page");
    await expect(page.locator("#nav-bas a[href='/']")).not.toHaveAttribute(
      "aria-current",
    );
  });

  test("sidebar_aria_current_absent_sur_liens_inactifs", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/");
    await expect(page.locator("main")).toBeVisible();
    const liensInactifs = page.locator(".barre-laterale-nav a:not([href='/'])");
    const nombre = await liensInactifs.count();
    for (let i = 0; i < nombre; i++) {
      await expect(liensInactifs.nth(i)).not.toHaveAttribute("aria-current");
    }
  });
});

test.describe("Scénario: accessibilité WCAG #742 — drawer mobile", () => {
  test.use({ storageState: FICHIER_AUTH_USER });

  test("drawer_a_aria_modal_true", async ({ page, isMobile }) => {
    if (!isMobile) test.skip();
    await page.goto("/");
    await expect(page.locator("main")).toBeVisible();
    await page.locator("#btn-nav-plus").click();
    const dialog = page.locator("#dialog-nav-plus");
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveAttribute("aria-modal", "true");
    await page.keyboard.press("Escape");
    await expect(dialog).not.toBeVisible();
  });

  test("drawer_aria_current_page_sur_lien_actif", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/artistes");
    await expect(page.locator("main")).toBeVisible();
    await page.locator("#btn-nav-plus").click();
    const dialog = page.locator("#dialog-nav-plus");
    await expect(dialog).toBeVisible();
    await expect(dialog.locator("a[href='/artistes']")).toHaveAttribute(
      "aria-current",
      "page",
    );
    await page.keyboard.press("Escape");
  });

  test("drawer_aria_current_absent_sur_liens_inactifs", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/artistes");
    await expect(page.locator("main")).toBeVisible();
    await page.locator("#btn-nav-plus").click();
    const dialog = page.locator("#dialog-nav-plus");
    await expect(dialog).toBeVisible();
    const liensInactifs = dialog.locator("a:not([href='/artistes'])");
    const nombre = await liensInactifs.count();
    for (let i = 0; i < nombre; i++) {
      await expect(liensInactifs.nth(i)).not.toHaveAttribute("aria-current");
    }
    await page.keyboard.press("Escape");
  });
});

test.describe("Scénario: accessibilité — champs nommés", () => {
  test.use({ storageState: FICHIER_AUTH_USER });

  test("preferences_champDepartements_aUnNomAccessible", async ({ page }) => {
    await page.goto("/parametres#preferences");
    await expect(
      page.getByRole("textbox", { name: "Départements", exact: true }),
    ).toBeVisible();
  });
});
