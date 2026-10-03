import { test, expect, request as playwrightRequest } from "@playwright/test";
import { FICHIER_AUTH_USER } from "./global-setup.js";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";
const E2E_USER_IDENTIFIANT = "e2euser";

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

test.describe("Scénario: navigation SPA vers paramètres", () => {
  test("accueil_vers_parametres_desktop_affiche_page", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/");
    await page.locator("nav.barre-laterale a[href='/parametres']").click();
    await expect(page).toHaveURL(/\/parametres/);
    await expect(
      page.getByRole("heading", { name: "Paramètres", level: 1 }),
    ).toBeVisible();
    await expect(page.locator("section#preferences")).toBeVisible();
  });

  test("accueil_vers_parametres_mobile_drawer", async ({ page, isMobile }) => {
    if (!isMobile) test.skip();
    await page.goto("/");
    await page.locator("button#btn-nav-plus").click();
    await page.locator("dialog#dialog-nav-plus a[href='/parametres']").click();
    await expect(page).toHaveURL(/\/parametres/);
    await expect(
      page.getByRole("heading", { name: "Paramètres", level: 1 }),
    ).toBeVisible();
    await expect(page.locator("section#preferences")).toBeVisible();
  });

  test("parametres_desktop_onglet_notifications_affiche_section", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/parametres");
    await page.locator("nav.param-onglets a[href='#notifications']").click();
    await expect(page.locator("section#notifications")).toBeVisible();
    await expect(page.locator("section#preferences")).not.toBeVisible();
  });

  test("parametres_desktop_onglet_compte_affiche_section", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/parametres");
    await page.locator("nav.param-onglets a[href='#compte']").click();
    await expect(page.locator("section#compte")).toBeVisible();
    await expect(page.locator("section#preferences")).not.toBeVisible();
  });

  test("parametres_desktop_onglet_donnees_affiche_section", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/parametres");
    await page.locator("nav.param-onglets a[href='#donnees']").click();
    await expect(page.locator("section#donnees")).toBeVisible();
    await expect(page.locator("section#preferences")).not.toBeVisible();
  });

  test("parametres_mobile_select_notifications_affiche_section", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/parametres");
    await page.selectOption("select.param-onglets-mobile", "#notifications");
    await expect(page.locator("section#notifications")).toBeVisible();
    await expect(page.locator("section#preferences")).not.toBeVisible();
  });

  test("parametres_mobile_select_compte_affiche_section", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/parametres");
    await page.selectOption("select.param-onglets-mobile", "#compte");
    await expect(page.locator("section#compte")).toBeVisible();
    await expect(page.locator("section#preferences")).not.toBeVisible();
  });

  test("parametres_mobile_select_donnees_affiche_section", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/parametres");
    await page.selectOption("select.param-onglets-mobile", "#donnees");
    await expect(page.locator("section#donnees")).toBeVisible();
    await expect(page.locator("section#preferences")).not.toBeVisible();
  });

  test("parametres_mobile_select_retour_preferences", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/parametres");
    await page.selectOption("select.param-onglets-mobile", "#compte");
    await page.selectOption("select.param-onglets-mobile", "#preferences");
    await expect(page.locator("section#preferences")).toBeVisible();
    await expect(page.locator("section#compte")).not.toBeVisible();
  });
});

test.describe("Scénario: API paramètres — stats et TOTP", () => {
  test("api_parametres_stats_retourne_compteurs", async ({ request }) => {
    const rep = await request.get("/api/parametres/stats");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(corps).toHaveProperty("nbArtistes");
    expect(corps).toHaveProperty("nbSalles");
    expect(typeof corps.nbArtistes).toBe("number");
    expect(typeof corps.nbSalles).toBe("number");
  });

  test("api_parametres_stats_sans_session_retourne401", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const rep = await ctx.request.get("/api/parametres/stats");
    expect(rep.status()).toBe(401);
    await ctx.close();
  });

  // #805 : PATCH /api/user/profile permettait de changer l'email sans confirmation, en
  // contournant le flux sécurisé POST /api/parametres/email-change. L'endpoint est retiré.
  test("api_user_profile_patch_nexistePlus", async ({ request }) => {
    const rep = await request.patch("/api/user/profile", {
      data: { email: "nouveau@exemple.fr" },
    });
    // GET /api/user/profile existe toujours (UserController) : le chemin est mappé mais pas
    // pour PATCH, donc HttpRequestMethodNotSupportedException -> 405 (GlobalExceptionHandler).
    expect(rep.status()).toBe(405);
  });

  test("api_totp_setup_retourne_qr_et_secret", async ({ request }) => {
    const rep = await request.get("/api/totp/setup");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(corps).toHaveProperty("otpUri");
    expect(corps).toHaveProperty("totpSecret");
    expect(corps).toHaveProperty("qrCodeSvg");
    expect(corps.qrCodeSvg).toContain("<svg");
  });

  test("api_totp_confirmer_code_invalide_retourne_400", async ({ request }) => {
    await request.get("/api/totp/setup");
    const rep = await request.post("/api/totp/confirmer", {
      data: { code: "000000" },
    });
    expect(rep.status()).toBe(400);
    const corps = await rep.json();
    expect(corps).toHaveProperty("erreur");
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
    expect(rep.status()).toBe(401);
    await ctx.close();
  });
});

test.describe("Scénario: import préférences — validation", () => {
  test("api_preferences_importer_jsonInvalide_retourne400", async ({
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
    expect(rep.status()).toBe(400);
  });

  test("api_preferences_importer_sans_session_retourne401", async ({
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
    expect(rep.status()).toBe(401);
    await ctx.close();
  });
});

test.describe("Scénario: API push — VAPID et validation", () => {
  test("push_get_retourne_objet_disponible", async ({ request }) => {
    const rep = await request.get("/api/push");
    expect(rep.status()).toBe(200);
    const corps = await rep.json();
    expect(corps).toHaveProperty("disponible");
  });

  test("push_desabonner_endpoint_vide_retourne_400", async ({ request }) => {
    const rep = await request.delete("/api/push", {
      data: { endpoint: "" },
    });
    expect(rep.status()).toBe(400);
    const corps = await rep.json();
    expect(corps).toHaveProperty("erreur");
  });

  test("push_abonner_sans_session_retourne401", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const rep = await ctx.request.post("/api/push", {
      data: {
        endpoint: "https://exemple.test/push",
        keys: { p256dh: "cle-test", auth: "auth-test" },
      },
    });
    expect(rep.status()).toBe(401);
    await ctx.close();
  });
});

test.describe("Scénario: Préférences — thème", () => {
  test("theme_desktop_sombre_affiche_classe_actif", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/parametres#preferences");
    await page.locator(".bouton-theme", { hasText: "Sombre" }).click();
    await expect(
      page.locator(".bouton-theme", { hasText: "Sombre" }),
    ).toHaveClass(/actif/);
    await expect(
      page.locator(".bouton-theme", { hasText: "Automatique" }),
    ).not.toHaveClass(/actif/);
    await page.locator(".bouton-theme", { hasText: "Automatique" }).click();
    await expect(
      page.locator(".bouton-theme", { hasText: "Automatique" }),
    ).toHaveClass(/actif/);
  });

  test("theme_desktop_clair_affiche_classe_actif", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/parametres#preferences");
    await page.locator(".bouton-theme", { hasText: "Clair" }).click();
    await expect(
      page.locator(".bouton-theme", { hasText: "Clair" }),
    ).toHaveClass(/actif/);
    await page.locator(".bouton-theme", { hasText: "Automatique" }).click();
  });

  test("theme_mobile_sombre_affiche_classe_actif", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/parametres");
    await page.selectOption("select.param-onglets-mobile", "#preferences");
    await page.locator(".bouton-theme", { hasText: "Sombre" }).click();
    await expect(
      page.locator(".bouton-theme", { hasText: "Sombre" }),
    ).toHaveClass(/actif/);
    await expect(
      page.locator(".bouton-theme", { hasText: "Automatique" }),
    ).not.toHaveClass(/actif/);
    await page.locator(".bouton-theme", { hasText: "Automatique" }).click();
  });

  test("theme_mobile_clair_affiche_classe_actif", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/parametres");
    await page.selectOption("select.param-onglets-mobile", "#preferences");
    await page.locator(".bouton-theme", { hasText: "Clair" }).click();
    await expect(
      page.locator(".bouton-theme", { hasText: "Clair" }),
    ).toHaveClass(/actif/);
    await page.locator(".bouton-theme", { hasText: "Automatique" }).click();
  });
});

test.describe("Scénario: Préférences — départements", () => {
  test("departements_code_inconnu_affiche_toast_avertissement", async ({
    page,
  }) => {
    // Attendre la fin du GET /api/user/config avant fill — sinon charger() peut
    // écraser departements.value après fill mais avant submit (race webkit).
    const [,] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes("/api/user/config") &&
          !r.url().includes("/departements") &&
          r.status() === 200,
      ),
      page.goto("/parametres#preferences"),
    ]);
    await expect(page.locator("section#preferences")).toBeVisible();
    await page.locator("input[placeholder='75, 92, 93…']").fill("ZZZZZ");
    await page.locator("section#preferences button[type='submit']").click();
    await expect(
      page.locator(".alerte-avertissement").filter({ hasText: "ignoré" }),
    ).toBeVisible({ timeout: 5000 });
  });

  test("api_departements_code_valide_sauvegarde_sans_avertissement", async ({
    request,
  }) => {
    const rep = await request.patch("/api/user/config/departements", {
      data: { departements: "75, 92" },
    });
    expect(rep.ok()).toBeTruthy();
    const corps = await rep.json();
    expect(corps).toHaveProperty("succes");
    expect(corps.ignores ?? []).toHaveLength(0);
  });
});

test.describe("Scénario: Préférences — filtre disponibles", () => {
  test("filtre_disponibles_toggle_change_etat", async ({ page }) => {
    await page.goto("/parametres#preferences");
    await page.waitForLoadState("networkidle");
    const toggle = page.locator(
      "input[type='checkbox'][aria-labelledby='label-filtre-disponibles']",
    );
    const etatInitial = await toggle.isChecked();
    await page.locator("label.bascule").click();
    await expect(toggle).toBeChecked({ checked: !etatInitial });
    await page.locator("label.bascule").click();
    await expect(toggle).toBeChecked({ checked: etatInitial });
  });

  test("api_filtres_patch_retourne_succes", async ({ request }) => {
    const rep = await request.patch("/api/user/config/filtres", {
      data: { filtreDisponibles: false },
    });
    expect(rep.ok()).toBeTruthy();
  });
});

test.describe("Scénario: API compte — identifiant", () => {
  test.afterEach(async () => {
    const ctx = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: FICHIER_AUTH_USER,
    });
    try {
      await ctx.patch("/api/user/identifiant", {
        data: { identifiant: E2E_USER_IDENTIFIANT },
      });
    } finally {
      await ctx.dispose();
    }
  });

  test("api_identifiant_trop_court_retourne_400", async ({ request }) => {
    const rep = await request.patch("/api/user/identifiant", {
      data: { identifiant: "ab" },
    });
    expect(rep.status()).toBe(400);
    const corps = await rep.json();
    expect(corps).toHaveProperty("erreur");
  });

  test("api_identifiant_caracteres_invalides_retourne_400", async ({
    request,
  }) => {
    const rep = await request.patch("/api/user/identifiant", {
      data: { identifiant: "kwaite@test" },
    });
    expect(rep.status()).toBe(400);
    const corps = await rep.json();
    expect(corps).toHaveProperty("erreur");
  });

  // #813 : ce test PATCH réellement l'identifiant du compte e2euser partagé (storageState par
  // défaut) — sans restauration, les tests suivants du run (ex. totp.e2e.ts, qui se connecte via
  // le login UI avec l'identifiant fixe "e2euser") échouent avec "Identifiant non trouvé".
  // Restaurer dans un finally pour survivre même si l'assertion échoue.
  test("api_identifiant_valide_retourne_succes", async ({ request }) => {
    const identTmp = `e2etmp${Date.now()}`.slice(0, 20);
    try {
      const rep = await request.patch("/api/user/identifiant", {
        data: { identifiant: identTmp },
      });
      expect(rep.ok()).toBeTruthy();
      const corps = await rep.json();
      expect(corps).toHaveProperty("succes");
    } finally {
      await request.patch("/api/user/identifiant", {
        data: { identifiant: "e2euser" },
      });
    }
  });

  // #813 : régression #712 — un identifiant avec point ou tiret était rejeté par un second
  // contrôle manuel plus strict que la validation Bean (RequeteIdentifiant). Restaure
  // l'identifiant partagé e2euser dans un finally, voir commentaire ci-dessus.
  test("api_identifiant_avecPointEtTiret_retourneSucces", async ({
    request,
  }) => {
    const identTmp = `e2e.tmp-${Date.now()}`.slice(0, 30);
    try {
      const rep = await request.patch("/api/user/identifiant", {
        data: { identifiant: identTmp },
      });
      expect(rep.ok()).toBeTruthy();
      const corps = await rep.json();
      expect(corps).toHaveProperty("succes");
    } finally {
      await request.patch("/api/user/identifiant", {
        data: { identifiant: "e2euser" },
      });
    }
  });
});

test.describe("Scénario: API compte — mot de passe", () => {
  test("api_mdp_mauvais_actuel_retourne_400", async ({ request }) => {
    const rep = await request.post("/api/user/password", {
      data: {
        motDePasseActuel: "mauvais-mot-de-passe-incorrect",
        motDePasseNouveau: "nouveau-mot-de-passe-valide-12chars",
      },
    });
    expect(rep.status()).toBe(400);
    const corps = await rep.json();
    expect(corps).toHaveProperty("erreur");
  });

  test("api_mdp_nouveau_trop_court_retourne_400", async ({ request }) => {
    const rep = await request.post("/api/user/password", {
      data: {
        motDePasseActuel: "Q%IypKZT37u77*Vr",
        motDePasseNouveau: "court",
      },
    });
    expect(rep.status()).toBe(400);
    const corps = await rep.json();
    expect(corps).toHaveProperty("erreur");
  });
});

test.describe("Scénario: Données — suppression de compte", () => {
  test("suppression_compte_bouton_dialog_desactive_sans_texte", async ({
    page,
  }) => {
    await page.goto("/parametres#donnees");
    await expect(page.locator("section#donnees")).toBeVisible();
    await page.locator("section#donnees button.bouton-danger").click();
    const dialog = page.locator("dialog#dialog-supprimer-compte");
    await expect(dialog).toBeVisible();
    await expect(dialog.locator("button.bouton-danger")).toBeDisabled();
  });

  test("suppression_compte_dialog_actif_avec_texte_correct", async ({
    page,
  }) => {
    await page.goto("/parametres#donnees");
    await expect(page.locator("section#donnees")).toBeVisible({
      timeout: 5000,
    });
    await page.locator("section#donnees button.bouton-danger").click();
    const dialog = page.locator("dialog#dialog-supprimer-compte");
    // Attendre explicitement le dialog (pattern déjà utilisé par le test précédent) — sans cela,
    // .fill() peut retenter contre un élément pas encore visible sous charge parallèle et finir
    // par expirer plutôt que de laisser l'auto-wait de Playwright suffire.
    await expect(dialog).toBeVisible();
    await dialog.locator("input#confirmation-suppression").fill("supprimer");
    await expect(dialog.locator("button.bouton-danger")).not.toBeDisabled();
    await dialog.locator("button.bouton-secondaire").click();
    await expect(dialog).not.toBeVisible();
  });

  test("suppression_compte_annuler_ferme_dialog", async ({ page }) => {
    await page.goto("/parametres#donnees");
    await expect(page.locator("section#donnees")).toBeVisible({
      timeout: 5000,
    });
    await page.locator("section#donnees button.bouton-danger").click();
    const dialog = page.locator("dialog#dialog-supprimer-compte");
    await expect(dialog).toBeVisible();
    await dialog.locator("button.bouton-secondaire").click();
    await expect(dialog).not.toBeVisible();
  });
});

test.describe("Scénario: champ identifiant — pattern HTML", () => {
  test("input_identifiant_pattern_accepte_point_et_tiret", async ({ page }) => {
    await page.goto("/parametres#compte");
    await expect(page.locator("section#compte")).toBeVisible();
    const input = page.locator("input#identifiant");
    await expect(input).toBeVisible();
    const pattern = await input.getAttribute("pattern");
    expect(pattern).toContain(".");
    expect(pattern).toContain("-");
  });

  // #813 : le test ci-dessus ne vérifiait que l'attribut HTML, jamais une soumission réelle —
  // le bug (contrôle serveur plus strict que le pattern) n'était donc jamais détecté. Restaure
  // l'identifiant partagé e2euser dans un finally — sans quoi le login UI par identifiant fixe
  // "e2euser" (ex. totp.e2e.ts) échoue pour le reste du run.
  test("input_identifiant_avecPointEtTiret_soumissionReussie", async ({
    page,
  }) => {
    await page.goto("/parametres#compte");
    await expect(page.locator("section#compte")).toBeVisible();
    const identTmp = `e2e.ui-${Date.now()}`.slice(0, 30);
    try {
      await page.locator("input#identifiant").fill(identTmp);
      await page.locator("form.form-inline button[type='submit']").click();
      await expect(
        page
          .locator(".alerte-succes")
          .filter({ hasText: "Identifiant modifié" }),
      ).toBeVisible({ timeout: 5000 });
    } finally {
      await page.request.patch("/api/user/identifiant", {
        data: { identifiant: "e2euser" },
      });
    }
  });
});

test.describe("Scénario: page TOTP enrollment", () => {
  test("totp_page_affiche_formulaire_confirmation", async ({ page }) => {
    await page.goto("/parametres/totp");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await expect(page.locator("input#code")).toBeVisible();
    await expect(page.locator(".totp-qr")).toBeVisible({ timeout: 10000 });
  });

  test("totp_page_annuler_retourne_parametres", async ({ page }) => {
    await page.goto("/parametres/totp");
    await page.locator("a[href='/parametres#compte']").click();
    await expect(page).toHaveURL(/\/parametres/);
  });
});
