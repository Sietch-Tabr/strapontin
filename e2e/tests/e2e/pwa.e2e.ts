import { test, expect } from "@playwright/test";

test.describe("Scénario: service worker — présence et accessibilité", () => {
  test("sw_fichier_accessible_sans_erreur", async ({ request }) => {
    const rep = await request.get("/sw.js");
    expect(rep.status()).toBe(200);
    const contentType = rep.headers()["content-type"] ?? "";
    expect(contentType).toContain("javascript");
  });

  test("sw_enregistre_apres_chargement", async ({ page, browserName }) => {
    // WebKit headless ne supporte pas navigator.serviceWorker de façon fiable
    test.skip(
      browserName === "webkit",
      "WebKit headless ne supporte pas navigator.serviceWorker de façon fiable",
    );
    await page.goto("/");
    await page.waitForFunction(
      async () => {
        if (!("serviceWorker" in navigator)) return false;
        const reg = await navigator.serviceWorker.getRegistration("/");
        return reg !== undefined;
      },
      undefined,
      { timeout: 10000 },
    );
  });
});
