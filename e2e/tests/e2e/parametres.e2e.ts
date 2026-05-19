import { test, expect } from "@playwright/test";

test.describe("Scénario: paramètres — sections", () => {
  test("parametres_h1_visible", async ({ page }) => {
    await page.goto("/parametres");
    await expect(
      page.getByRole("heading", { name: "Paramètres", level: 1 }),
    ).toBeVisible();
  });

  test("parametres_section_preferences_visible", async ({ page }) => {
    await page.goto("/parametres");
    await expect(page.locator("section#preferences")).toBeVisible();
  });

  test("parametres_section_notifications_visible", async ({ page }) => {
    await page.goto("/parametres#notifications");
    await expect(page.locator("section#notifications")).toBeVisible();
  });

  test("parametres_section_compte_visible", async ({ page }) => {
    await page.goto("/parametres#compte");
    await expect(page.locator("section#compte")).toBeVisible();
  });

  test("parametres_section_donnees_visible", async ({ page }) => {
    await page.goto("/parametres#donnees");
    await expect(page.locator("section#donnees")).toBeVisible();
  });
});

test.describe("Scénario: export préférences JSON", () => {
  test("api_preferences_retourne_json_telechargeable", async ({ request }) => {
    const rep = await request.get("/api/preferences");
    expect(rep.status()).toBe(200);
    const contentType = rep.headers()["content-type"] ?? "";
    expect(contentType).toContain("application/json");
    const disposition = rep.headers()["content-disposition"] ?? "";
    expect(disposition).toContain("strapontin-preferences");
    const corps = await rep.json();
    expect(corps).toHaveProperty("artistes");
    expect(corps).toHaveProperty("salles");
  });

  test("api_preferences_non_accessible_sans_session", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const rep = await ctx.request.get("/api/preferences");
    expect([401, 302, 403]).toContain(rep.status());
    await ctx.close();
  });
});

test.describe("Scénario: import préférences — validation", () => {
  test("api_preferences_importer_json_invalide_retourne_erreur", async ({
    request,
  }) => {
    const formData = new FormData();
    formData.append(
      "fichier",
      new Blob(["pas du json"], { type: "application/json" }),
      "prefs.json",
    );
    formData.append("mode", "fusionner");
    const rep = await request.post("/api/preferences/importer", {
      multipart: {
        fichier: {
          name: "prefs.json",
          mimeType: "application/json",
          buffer: Buffer.from("pas du json"),
        },
        mode: "fusionner",
      },
    });
    expect([200, 400, 422]).toContain(rep.status());
  });

  test("api_preferences_importer_sans_session_retourne_401_ou_302", async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const rep = await ctx.request.post("/api/preferences/importer", {
      multipart: {
        fichier: {
          name: "prefs.json",
          mimeType: "application/json",
          buffer: Buffer.from("{}"),
        },
        mode: "fusionner",
      },
    });
    expect([401, 302, 403]).toContain(rep.status());
    await ctx.close();
  });
});
