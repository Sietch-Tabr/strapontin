import { test, expect } from "@playwright/test";

test.describe("Scénario: service worker — présence et accessibilité", () => {
  test("sw_fichier_accessible_sans_erreur", async ({ request }) => {
    const rep = await request.get("/sw.js");
    expect(rep.status()).toBe(200);
    const contentType = rep.headers()["content-type"] ?? "";
    expect(contentType).toContain("javascript");
  });

  test("sw_enregistre_et_actif_apres_chargement", async ({
    page,
    browserName,
  }) => {
    test.skip(
      browserName === "webkit",
      "WebKit headless ne supporte pas navigator.serviceWorker de façon fiable",
    );
    await page.goto("/");
    await page.waitForFunction(
      async () => {
        if (!("serviceWorker" in navigator)) return false;
        const reg = await navigator.serviceWorker.getRegistration("/");
        return reg !== undefined && reg.active !== null;
      },
      undefined,
      { timeout: 15000 },
    );
  });
});

test.describe("Scénario: PWA — manifest et installabilité", () => {
  test("manifest_json_accessible_et_valide", async ({ request }) => {
    const rep = await request.get("/manifest.json");
    expect(rep.status()).toBe(200);
    const contentType = rep.headers()["content-type"] ?? "";
    expect(contentType).toContain("json");
    const corps = await rep.json();
    expect(corps).toHaveProperty("name");
    expect(corps).toHaveProperty("start_url");
    expect(corps).toHaveProperty("display");
    expect(corps.icons).toBeInstanceOf(Array);
    expect(corps.icons.length).toBeGreaterThan(0);
  });

  test("manifest_json_contient_launch_handler", async ({ request }) => {
    const corps = await (await request.get("/manifest.json")).json();
    expect(corps).toHaveProperty("launch_handler");
    expect(corps.launch_handler).toHaveProperty("client_mode");
    expect(corps.launch_handler.client_mode).toBe("navigate-existing");
  });

  test("manifest_json_contient_categories", async ({ request }) => {
    const corps = await (await request.get("/manifest.json")).json();
    expect(corps.categories).toBeInstanceOf(Array);
    expect(corps.categories.length).toBeGreaterThan(0);
  });

  test("manifest_json_icone_maskable_separee_de_any", async ({ request }) => {
    const corps = await (await request.get("/manifest.json")).json();
    const icons = corps.icons as { src: string; purpose?: string }[];
    const maskable = icons.filter((i) => i.purpose === "maskable");
    const any = icons.filter((i) => i.purpose === "any");
    expect(maskable.length).toBeGreaterThan(0);
    expect(any.length).toBeGreaterThan(0);
    const purposes = icons.map((i) => i.purpose ?? "");
    expect(purposes).not.toContain("any maskable");
  });

  test("manifest_json_icons_accessibles", async ({ request }) => {
    const manifest = await (await request.get("/manifest.json")).json();
    const vus = new Set<string>();
    for (const icon of manifest.icons as { src: string }[]) {
      if (vus.has(icon.src)) continue;
      vus.add(icon.src);
      const rep = await request.get(icon.src);
      expect(rep.status()).toBe(200);
    }
  });

  test("index_html_contient_lien_manifest", async ({ request }) => {
    const rep = await request.get("/");
    expect(rep.status()).toBe(200);
    const html = await rep.text();
    expect(html).toContain('rel="manifest"');
    expect(html).toMatch(/href="\/manifest[^"]*\.json"/);
  });
});
