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
    // Délai réel du toast succès : 5000ms (useToasts.js delaiParType) — marge portée à 12s
    // (au lieu de 8s) pour absorber la latence réseau/rendu sous charge parallèle complète de la
    // suite (jalon 2.6.4, volume de tests fortement accru), sans changer le comportement vérifié.
    await expect(page.locator(".alerte-succes").first()).toBeHidden({
      timeout: 12000,
    });
  });

  // #774 : vérification statique du fichier CSS livré (pas de comportement spécifique navigateur —
  // Playwright ne permet pas de simuler env(safe-area-inset-bottom), cf. commentaire dans
  // components.css). Restreint à chromium, même logique que les autres vérifications statiques de ce
  // module (cf. catalogue.e2e.ts).
  // Depuis le passage du CSS en mobile-first (#1065), la règle safe-area vit désormais dans la
  // déclaration de base (mobile = comportement par défaut) et non plus dans un bloc
  // @media (max-width: 1024px) — seule la surcharge desktop (min-width: 1025px, sans safe-area)
  // reste dans un media query.
  test(
    "règle CSS bottom du toast mobile inclut la safe-area iOS",
    { tag: "@chromium-only" },
    async ({ page, browserName }) => {
      test.skip(
        browserName !== "chromium",
        "vérification statique du CSS livré — chromium only",
      );
      await page.goto("/");
      const contientSafeArea = await page.evaluate(() => {
        for (const feuille of Array.from(document.styleSheets)) {
          let regles;
          try {
            regles = Array.from(feuille.cssRules);
          } catch {
            continue;
          }
          for (const regle of regles) {
            if (
              regle instanceof CSSStyleRule &&
              regle.selectorText.includes("alerte") &&
              regle.cssText.includes("safe-area-inset-bottom")
            ) {
              return true;
            }
          }
        }
        return false;
      });
      expect(contientSafeArea).toBe(true);
    },
  );
});

test.describe("Toasts — actions admin", () => {
  test.use({ storageState: FICHIER_AUTH_ADMIN });

  test("toast succes admin relancer fetches", async ({ page }, testInfo) => {
    // Déclenche de vraies récupérations : quota Ticketmaster et absences des concerts.
    test.skip(
      testInfo.project.name !== "chromium",
      "récupérations réelles, un seul navigateur",
    );
    await page.goto("/admin#jobs");
    await expect(page.locator(".etat-chargement").first()).not.toBeVisible({
      timeout: 5000,
    });
    const bouton = page.getByRole("button", {
      name: "Relancer les fetches maintenant",
    });
    await bouton.scrollIntoViewIfNeeded();
    await bouton.click();
    await expect(page.locator(".alerte-succes")).toContainText(
      "Fetches relancés",
      { timeout: 5000 },
    );
  });
});
