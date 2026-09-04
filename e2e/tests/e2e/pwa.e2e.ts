import { test, expect } from "@playwright/test";

test.describe("Scénario: service worker — présence et accessibilité", () => {
  test("sw_fichier_accessible_sans_erreur", async ({ request }) => {
    const rep = await request.get("/sw.js");
    expect(rep.status()).toBe(200);
    const contentType = rep.headers()["content-type"] ?? "";
    expect(contentType).toContain("javascript");
  });

  test("sw_servi_avec_no_cache", async ({ request }) => {
    const rep = await request.get("/sw.js");
    const cacheControl = rep.headers()["cache-control"] ?? "";
    expect(cacheControl).toContain("no-cache");
  });

  test("index_html_servi_avec_no_cache", async ({ request }) => {
    const rep = await request.get("/");
    const cacheControl = rep.headers()["cache-control"] ?? "";
    expect(cacheControl).toContain("no-cache");
  });

  test("images_servies_avec_cache_7_jours", async ({ request }) => {
    const rep = await request.get("/images/favicon.png");
    expect(rep.status()).toBe(200);
    const cacheControl = rep.headers()["cache-control"] ?? "";
    expect(cacheControl).toContain("max-age=604800");
    expect(cacheControl).toContain("public");
  });

  test("icones_servies_avec_cache_7_jours", async ({ request }) => {
    const rep = await request.get("/icons/lucide-sprite.svg");
    expect(rep.status()).toBe(200);
    const cacheControl = rep.headers()["cache-control"] ?? "";
    expect(cacheControl).toContain("max-age=604800");
    expect(cacheControl).toContain("public");
  });

  // Jalon 2.6.6 : ce test était un faux positif — `page.waitForFunction()` avec un prédicat
  // async résout immédiatement dès que la fonction retourne un objet (la Promise elle-même,
  // toujours "truthy"), sans attendre sa valeur résolue. Il "passait" en ~100ms quel que soit
  // l'état réel du service worker. Corrigé par un polling manuel qui attend explicitement la
  // valeur résolue à chaque itération — révèle alors que `navigator.serviceWorker` n'existe
  // jamais dans cet environnement : le LXC dev est servi en HTTP sur une IP privée
  // (192.168.20.78), donc `window.isSecureContext` est `false` et l'API Service Worker n'est
  // jamais exposée par le navigateur (elle exige HTTPS ou `localhost`). Limitation
  // d'environnement de test, pas un bug applicatif (prod sert bien en HTTPS) — testé à la place
  // en Vitest (`useMiseAJourServiceWorker.test.js`) avec un conteneur service worker simulé.
  test("sw_enregistre_et_actif_apres_chargement", async ({
    page,
    browserName,
  }) => {
    test.skip(
      browserName === "webkit",
      "WebKit headless ne supporte pas navigator.serviceWorker de façon fiable",
    );
    await page.goto("/");

    const contexteSecurise = await page.evaluate(() => window.isSecureContext);
    test.skip(
      !contexteSecurise,
      "Contexte non sécurisé (HTTP sur IP privée) — l'API Service Worker n'est jamais exposée" +
        " ici, indépendamment du code applicatif. Voir prod (HTTPS) pour un contexte sécurisé.",
    );

    let actif = false;
    const debut = Date.now();
    while (Date.now() - debut < 15000 && !actif) {
      actif = await page.evaluate(async () => {
        if (!("serviceWorker" in navigator)) return false;
        const reg = await navigator.serviceWorker.getRegistration("/");
        return reg !== undefined && reg.active !== null;
      });
      if (!actif) await page.waitForTimeout(200);
    }
    expect(actif).toBe(true);
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
    for (const icone of manifest.icons as { src: string }[]) {
      if (vus.has(icone.src)) continue;
      vus.add(icone.src);
      const rep = await request.get(icone.src);
      expect(rep.status()).toBe(200);
    }
  });

  // #880 : manifest.shortcuts jamais testé — ni sa présence, ni ses URLs, ni l'accessibilité de
  // ses icônes.
  test("manifest_json_contient_shortcuts_valides", async ({ request }) => {
    const corps = await (await request.get("/manifest.json")).json();
    expect(corps.shortcuts).toBeInstanceOf(Array);
    expect(corps.shortcuts.length).toBeGreaterThan(0);

    const urlsAttendues = ["/spectacles", "/decouverte", "/?nouveau=true"];
    const urls = (corps.shortcuts as { url: string }[]).map((s) => s.url);
    for (const url of urlsAttendues) {
      expect(urls).toContain(url);
    }

    for (const shortcut of corps.shortcuts as {
      name: string;
      url: string;
      icons: { src: string }[];
    }[]) {
      expect(shortcut.name).toBeTruthy();
      expect(shortcut.icons.length).toBeGreaterThan(0);
      for (const icone of shortcut.icons) {
        const repIcone = await request.get(icone.src);
        expect(repIcone.status()).toBe(200);
      }
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

test.describe("Scénario: bannière d'installation PWA (usePwaInstall.js)", () => {
  // beforeinstallprompt n'est réellement déclenché par aucun navigateur de test Playwright
  // (heuristiques d'engagement Chrome) — un Event synthétique dispatché après chargement
  // reproduit fidèlement le comportement du listener (preventDefault + logique de seuil), sans
  // dépendre de .prompt()/.userChoice (propres à l'événement natif, hors périmètre ici).
  function dispatcherEvenementDiffere(page: import("@playwright/test").Page) {
    return page.evaluate(() => {
      window.dispatchEvent(
        new Event("beforeinstallprompt", { cancelable: true }),
      );
    });
  }

  test("pwaInstall_sousLeSeuilDeVisites_banniereMasquee", async ({ page }) => {
    await page.addInitScript(() =>
      localStorage.setItem("pwa_install_visites", "0"),
    );
    await page.goto("/");
    await dispatcherEvenementDiffere(page);
    await expect(page.locator(".pwa-install-banniere")).not.toBeVisible();
  });

  test("pwaInstall_seuilAtteint_banniereAffichee", async ({ page }) => {
    await page.addInitScript(() =>
      localStorage.setItem("pwa_install_visites", "2"),
    );
    await page.goto("/");
    await dispatcherEvenementDiffere(page);
    await expect(page.locator(".pwa-install-banniere")).toBeVisible({
      timeout: 3000,
    });
  });

  test("pwaInstall_plusTard_masqueEtReinitialiseLeCompteurDeVisites", async ({
    page,
  }) => {
    await page.addInitScript(() =>
      localStorage.setItem("pwa_install_visites", "2"),
    );
    await page.goto("/");
    await dispatcherEvenementDiffere(page);
    const banniere = page.locator(".pwa-install-banniere");
    await expect(banniere).toBeVisible({ timeout: 3000 });

    await banniere.getByRole("button", { name: "Plus tard" }).click();
    await expect(banniere).not.toBeVisible();
    const visites = await page.evaluate(() =>
      localStorage.getItem("pwa_install_visites"),
    );
    expect(visites).toBe("0");
  });

  test("pwaInstall_dejaInstalle_neReapparaitJamaisMemeAuSeuil", async ({
    page,
  }) => {
    await page.addInitScript(() => {
      localStorage.setItem("pwa_install_visites", "2");
      localStorage.setItem("pwa_install_installe", "1");
    });
    await page.goto("/");
    await dispatcherEvenementDiffere(page);
    await expect(page.locator(".pwa-install-banniere")).not.toBeVisible();
  });
});

test.describe("Scénario: PWA — cache HTTP assets Vite", () => {
  test("assets_vite_servis_avec_cache_immutable", async ({ request }) => {
    const indexRep = await request.get("/");
    const html = await indexRep.text();
    const m = html.match(/\/assets\/index-[^"]+\.js/);
    if (!m) {
      test.skip(true, "aucun bundle Vite trouvé dans index.html");
      return;
    }
    const rep = await request.get(m[0]);
    expect(rep.status()).toBe(200);
    const cacheControl = rep.headers()["cache-control"] ?? "";
    expect(cacheControl).toContain("immutable");
    expect(cacheControl).toContain("max-age");
  });
});
