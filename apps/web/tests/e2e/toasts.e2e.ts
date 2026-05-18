import { test, expect } from "@playwright/test";

async function soumettreDepartements(page: import("@playwright/test").Page) {
  await page.goto("/parametres");
  const input = page.locator('input[name="departements"]');
  await input.waitFor({ state: "visible" });
  await input.fill((await input.inputValue()) || "75");
  // regex strict : ne matche pas /parametres/departements (URL intermédiaire du POST)
  // waitUntil:"load" garantit l'exécution des scripts defer
  await Promise.all([
    page.waitForURL(/\/parametres(?:\?|#|$)/, { waitUntil: "load" }),
    page.click(
      'form[action*="/parametres/departements"] button[type="submit"]',
    ),
  ]);
}

test.describe("Toasts (Popover API)", () => {
  test("toast succes apparaît après enregistrement paramètres département", async ({
    page,
  }) => {
    await soumettreDepartements(page);
    const toast = page.locator(".alerte-succes");
    await expect(toast.first()).toBeVisible({ timeout: 3000 });
    await expect(toast.first()).toContainText("Paramètres enregistrés");
  });

  test("toast succes admin relancer fetches", async ({ page }) => {
    await page.goto("/admin");
    await page
      .getByRole("button", { name: "Déclencher les fetches maintenant" })
      .click();
    const toast = page.locator(".alerte-succes");
    await expect(toast.first()).toBeVisible({ timeout: 15000 });
    await expect(toast.first()).toContainText("Fetches déclenchés");
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
      await page.locator(".toast-fermer").click();
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
    await page.waitForFunction(
      () => {
        const el = document.querySelector(".alerte-succes");
        // Le toast DOIT avoir été ouvert (JS a tourné) puis fermé par le timer
        return (
          !!el && !el.matches(":popover-open") && el.hasAttribute("popover")
        );
      },
      { timeout: 6000 },
    );
  });
});
