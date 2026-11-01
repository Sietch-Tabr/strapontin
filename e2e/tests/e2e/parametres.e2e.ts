import {
  test,
  expect,
  request as playwrightRequest,
  type Page,
} from "@playwright/test";
import { FICHIER_AUTH_USER } from "./global-setup.js";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";
const E2E_USER_IDENTIFIANT = "e2euser";

test.describe("Scénario: paramètres — nom des appareils", () => {
  test("compte_appareilsConnectes_nommeLAppareilEnClair", async ({ page }) => {
    await page.goto("/parametres#compte");
    const section = page.locator("section#compte");
    const liste = section.locator(".appareil-info").first();

    await expect(liste).toBeVisible();
    // Navigateur et système, jamais le User-Agent brut ni un jeton comme « Mac OS X » ou « Edg ».
    await expect(liste).toContainText(
      /(Chrome|Safari|Firefox|Edge) sur (macOS|Windows|Linux|Android|iPhone|iPad)/,
    );
    await expect(liste).not.toContainText("Mozilla/5.0");
    await expect(liste).not.toContainText("Mac OS X");
  });

  test("api_appelsDuFront_annoncentLeModeDAffichage", async ({ page }) => {
    const requete = page.waitForRequest(
      (r) =>
        r.url().includes("/api/") &&
        r.headers()["x-mode-affichage"] !== undefined,
    );
    await page.goto("/parametres#compte");

    // Onglet de navigateur dans les tests : le serveur distingue ainsi une application installée.
    expect((await requete).headers()["x-mode-affichage"]).toBe("navigateur");
  });
});

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

test.describe("Scénario: paramètres — notifications, mise en page et sortie", () => {
  async function ouvrirNotifications(page: Page) {
    // Sans ancre dans l'URL : WebKit double la navigation d'un goto avec fragment.
    await page.goto("/parametres");
    const selecteur = page.locator("select.param-onglets-mobile");
    if (await selecteur.isVisible()) {
      await selecteur.selectOption("#notifications");
    } else {
      await page.locator('a.param-onglet[href="#notifications"]').click();
    }
    await expect(page.locator("section#notifications")).toBeVisible();
    await expect(
      page.getByRole("radio", { name: "Récapitulatif quotidien (12h)" }),
    ).toBeVisible();
  }

  async function modifierLaFrequence(page: Page) {
    const quotidien = page.getByRole("radio", {
      name: "Récapitulatif quotidien (12h)",
    });
    const hebdomadaire = page.getByRole("radio", {
      name: "Récapitulatif hebdomadaire (dimanche 12h)",
    });
    // Choisir la fréquence qui n'est pas celle enregistrée.
    const cible = (await quotidien.isChecked()) ? hebdomadaire : quotidien;
    await cible.check();
  }

  async function quitterVersLAccueil(page: Page) {
    await page.locator('a[href="/"]').visible().first().click();
  }

  test("parametres_notifications_frequence_aLeCadreTitreDesAutresBlocs", async ({
    page,
  }) => {
    await ouvrirNotifications(page);
    const bloc = page.locator("#notifications .param-bloc", {
      has: page.getByRole("heading", { name: "Fréquence", level: 3 }),
    });
    await expect(bloc.locator("fieldset")).toHaveCount(0);
    await expect(bloc.getByRole("radiogroup")).toBeVisible();
  });

  test("parametres_notifications_modification_afficheLEtatNonEnregistre", async ({
    page,
  }) => {
    await ouvrirNotifications(page);
    await expect(page.getByText("Modifications non enregistrées.")).toHaveCount(
      0,
    );

    await modifierLaFrequence(page);

    await expect(
      page.getByText("Modifications non enregistrées."),
    ).toBeVisible();
  });

  test("parametres_notifications_quitterSansModification_nePoseAucuneQuestion", async ({
    page,
  }) => {
    await ouvrirNotifications(page);

    await quitterVersLAccueil(page);

    await expect(page).not.toHaveURL(/\/parametres/);
    await expect(page.locator("dialog[open]")).toHaveCount(0);
  });

  test("parametres_notifications_quitterAvecModification_proposeEnregistrerAbandonnerOuRester", async ({
    page,
  }) => {
    await ouvrirNotifications(page);
    await modifierLaFrequence(page);

    await quitterVersLAccueil(page);

    const dialogue = page.getByRole("dialog", {
      name: "Modifications non enregistrées",
    });
    await expect(dialogue).toBeVisible();
    await expect(
      dialogue.getByRole("button", { name: "Enregistrer" }),
    ).toBeVisible();
    await expect(
      dialogue.getByRole("button", { name: "Abandonner les modifications" }),
    ).toBeVisible();

    await dialogue.getByRole("button", { name: "Rester sur la page" }).click();
    await expect(page).toHaveURL(/\/parametres/);
    await expect(
      page.getByText("Modifications non enregistrées."),
    ).toBeVisible();

    await quitterVersLAccueil(page);
    await page
      .getByRole("dialog", { name: "Modifications non enregistrées" })
      .getByRole("button", { name: "Abandonner les modifications" })
      .click();
    await expect(page).not.toHaveURL(/\/parametres/);
  });
});

test.describe("Scénario: paramètres — boutons de formulaire", () => {
  // Un bouton de formulaire garde la largeur de son libellé : il ne s'étire pas sur celle du
  // formulaire (28 rem), sauf `bouton-pleine-largeur`.
  for (const section of ["preferences", "compte", "donnees"]) {
    test(`parametres_${section}_boutonsDeFormulaire_gardentLaLargeurDeLeurLibelle`, async ({
      page,
    }) => {
      await page.goto(`/parametres#${section}`);
      await expect(page.locator(`section#${section}`)).toBeVisible();
      const formulaires = page.locator(`section#${section} form.form-vertical`);
      // Le contenu de la section se charge après son apparition : on attend le premier formulaire
      // avant de compter, sinon le décompte lit une section encore vide.
      await expect(formulaires.first()).toBeVisible();
      const nombre = await formulaires.count();
      expect(nombre).toBeGreaterThan(0);
      for (let i = 0; i < nombre; i++) {
        const formulaire = formulaires.nth(i);
        const largeurFormulaire = (await formulaire.boundingBox())!.width;
        const boutons = formulaire.locator(
          ":scope > button.bouton:not(.bouton-pleine-largeur)",
        );
        for (let j = 0; j < (await boutons.count()); j++) {
          const largeurBouton = (await boutons.nth(j).boundingBox())!.width;
          expect(largeurBouton).toBeLessThan(largeurFormulaire * 0.8);
        }
      }
    });
  }

  test("parametres_compte_champCodeTotp_proportionneAuCode", async ({
    page,
  }) => {
    await page.goto("/parametres#compte");
    const champ = page.locator("input#totp-desact-code");
    // Le compte de test n'a le TOTP actif que pendant certains scénarios.
    if ((await champ.count()) === 0) test.skip();
    expect((await champ.boundingBox())!.width).toBeLessThan(200);
    await expect(champ).not.toHaveAttribute("placeholder", /.+/);
    await expect(page.locator("#aide-totp-desact")).toContainText(
      "application d'authentification",
    );
  });
});

test.describe("Scénario: paramètres — Données, libellés et mode d'import", () => {
  test("parametres_donnees_export_sIntituleTelecharger", async ({ page }) => {
    await page.goto("/parametres#donnees");
    await expect(
      page.getByRole("link", { name: "Télécharger", exact: true }),
    ).toBeVisible();
    await expect(page.locator("section#donnees")).not.toContainText(
      "Fichier JSON",
    );
    await expect(page.locator("section#donnees")).not.toContainText("Écraser");
  });

  // Les deux modes sont visibles en même temps, chacun avec son effet ; le remplacement, qui
  // supprime des données, est signalé avant l'import.
  test("parametres_donnees_modeImport_boutonsBasculeAvecExplications", async ({
    page,
  }) => {
    await page.goto("/parametres#donnees");
    const groupe = page.getByRole("group", { name: "Mode d'import" });
    const fusionner = groupe.getByRole("button", { name: "Fusionner" });
    const remplacer = groupe.getByRole("button", { name: "Remplacer" });
    await expect(fusionner).toHaveAttribute("aria-pressed", "true");
    await expect(remplacer).toHaveAttribute("aria-pressed", "false");
    await expect(groupe).toContainText("Ajoute les artistes et salles");
    await expect(groupe).toContainText(
      "Attention : remplace tous vos artistes",
    );
    await remplacer.click();
    await expect(remplacer).toHaveAttribute("aria-pressed", "true");
    await expect(fusionner).toHaveAttribute("aria-pressed", "false");
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
    await page
      .locator(".theme-choix .bouton-bascule", { hasText: "Sombre" })
      .click();
    await expect(
      page.locator(".theme-choix .bouton-bascule", { hasText: "Sombre" }),
    ).toHaveClass(/actif/);
    await expect(
      page.locator(".theme-choix .bouton-bascule", { hasText: "Automatique" }),
    ).not.toHaveClass(/actif/);
    await page
      .locator(".theme-choix .bouton-bascule", { hasText: "Automatique" })
      .click();
    await expect(
      page.locator(".theme-choix .bouton-bascule", { hasText: "Automatique" }),
    ).toHaveClass(/actif/);
  });

  test("theme_desktop_clair_affiche_classe_actif", async ({
    page,
    isMobile,
  }) => {
    if (isMobile) test.skip();
    await page.goto("/parametres#preferences");
    await page
      .locator(".theme-choix .bouton-bascule", { hasText: "Clair" })
      .click();
    await expect(
      page.locator(".theme-choix .bouton-bascule", { hasText: "Clair" }),
    ).toHaveClass(/actif/);
    await page
      .locator(".theme-choix .bouton-bascule", { hasText: "Automatique" })
      .click();
  });

  test("theme_mobile_sombre_affiche_classe_actif", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/parametres");
    await page.selectOption("select.param-onglets-mobile", "#preferences");
    await page
      .locator(".theme-choix .bouton-bascule", { hasText: "Sombre" })
      .click();
    await expect(
      page.locator(".theme-choix .bouton-bascule", { hasText: "Sombre" }),
    ).toHaveClass(/actif/);
    await expect(
      page.locator(".theme-choix .bouton-bascule", { hasText: "Automatique" }),
    ).not.toHaveClass(/actif/);
    await page
      .locator(".theme-choix .bouton-bascule", { hasText: "Automatique" })
      .click();
  });

  test("theme_mobile_clair_affiche_classe_actif", async ({
    page,
    isMobile,
  }) => {
    if (!isMobile) test.skip();
    await page.goto("/parametres");
    await page.selectOption("select.param-onglets-mobile", "#preferences");
    await page
      .locator(".theme-choix .bouton-bascule", { hasText: "Clair" })
      .click();
    await expect(
      page.locator(".theme-choix .bouton-bascule", { hasText: "Clair" }),
    ).toHaveClass(/actif/);
    await page
      .locator(".theme-choix .bouton-bascule", { hasText: "Automatique" })
      .click();
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
  test("api_mdp_mauvais_actuel_retourne_400", async ({
    request,
    browserName,
    isMobile,
  }) => {
    // Chaque essai erroné compte dans la limite de 5 par 15 min et par compte.
    test.skip(
      browserName !== "chromium" || isMobile,
      "limite d'échecs par compte — chromium desktop uniquement",
    );
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

// Configuration servie telle quelle, sauf les préférences de notification du scénario : sans clé
// Resend (dev), la case Email serait désactivée.
async function servirConfigNotifications(
  page: import("@playwright/test").Page,
  surcharges: Record<string, unknown>,
) {
  await page.route("**/api/user/config", async (route) => {
    if (route.request().method() !== "GET") {
      return route.continue();
    }
    const reponse = await route.fetch();
    const config = await reponse.json();
    return route.fulfill({
      response: reponse,
      json: { ...config, resendDisponible: true, ...surcharges },
    });
  });
}

async function servirPush(
  page: import("@playwright/test").Page,
  abonnements: unknown[],
) {
  // Le dev est servi en HTTP sur une IP privée, contexte non sécurisé où le navigateur
  // n'expose ni service worker ni push : la section push ne s'affiche qu'avec cette émulation.
  await page.addInitScript(() => {
    if (!("serviceWorker" in navigator)) {
      Object.defineProperty(navigator, "serviceWorker", {
        configurable: true,
        value: {
          register: () => Promise.resolve(),
          addEventListener: () => {},
          removeEventListener: () => {},
          ready: Promise.resolve({
            pushManager: { getSubscription: () => Promise.resolve(null) },
          }),
        },
      });
    }
    if (!("PushManager" in window)) {
      Object.defineProperty(window, "PushManager", {
        configurable: true,
        value: class {},
      });
    }
    // En contexte non sécurisé la permission réelle est « denied » : on la force à « default ».
    if (!("Notification" in window)) {
      Object.defineProperty(window, "Notification", {
        configurable: true,
        value: {},
      });
    }
    Object.defineProperty(window.Notification, "permission", {
      configurable: true,
      get: () => "default",
    });
  });
  await page.route("**/api/push/abonnements", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(abonnements),
    }),
  );
  await page.route("**/api/push", (route) =>
    route.request().method() === "GET"
      ? route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ disponible: true, clePublique: "cle" }),
        })
      : route.continue(),
  );
}

test.describe("Scénario: notifications par canal", () => {
  // Sans appareil abonné, le canal push est décoché et ses cases ne se cochent pas : l'utilisateur
  // ne croit pas recevoir ce qu'il ne recevra pas.
  test("notifications_sansAppareil_canalPushDecocheEtCasesDesactivees", async ({
    page,
  }) => {
    await servirPush(page, []);
    await servirConfigNotifications(page, {
      frequenceNotifications: "quotidien",
      notifArtistes: true,
      notifArtistesPush: false,
      notifSallesPush: false,
    });
    await page.goto("/parametres#notifications");
    const section = page.locator("section#notifications");
    await expect(section).toBeVisible();

    await expect(
      section.getByText("Aucun appareil n'est abonné"),
    ).toBeVisible();
    await expect(
      section.getByRole("checkbox", {
        name: "Recevoir les notifications push",
      }),
    ).not.toBeChecked();
    await expect(
      section
        .getByRole("checkbox", { name: "Concerts des artistes suivis" })
        .nth(1),
    ).toBeDisabled();
  });

  test("notifications_frequenceJamais_decocheEtDesactiveLesDeuxCanaux", async ({
    page,
  }) => {
    await servirPush(page, [
      {
        endpoint: "https://fcm.googleapis.com/fcm/send/autre-appareil",
        creeLe: "2026-10-01T10:00:00Z",
        appareil: "Firefox sur Linux",
        enEchecDepuis: null,
        cause: null,
      },
    ]);
    await servirConfigNotifications(page, {
      frequenceNotifications: "quotidien",
      notifArtistes: true,
      notifArtistesPush: true,
      notifSallesPush: true,
    });
    await page.goto("/parametres#notifications");
    const section = page.locator("section#notifications");
    await expect(section).toBeVisible();
    const emailArtistes = section
      .getByRole("checkbox", { name: "Concerts des artistes suivis" })
      .nth(0);
    const pushArtistes = section
      .getByRole("checkbox", { name: "Concerts des artistes suivis" })
      .nth(1);
    await expect(emailArtistes).toBeChecked();
    await expect(pushArtistes).toBeChecked();

    await section.getByRole("radio", { name: "Jamais" }).check();

    await expect(emailArtistes).not.toBeChecked();
    await expect(pushArtistes).not.toBeChecked();
    await expect(
      section.getByRole("checkbox", {
        name: "Recevoir les notifications push",
      }),
    ).not.toBeChecked();
  });

  test("notifications_recapitulatifsAnnoncesAMidi", async ({ page }) => {
    await page.goto("/parametres#notifications");
    const section = page.locator("section#notifications");
    await expect(
      section.getByRole("radio", { name: "Récapitulatif quotidien (12h)" }),
    ).toBeVisible();
    await expect(
      section.getByRole("radio", {
        name: "Récapitulatif hebdomadaire (dimanche 12h)",
      }),
    ).toBeVisible();
    await expect(
      section.getByText("entre 22h et 7h", { exact: false }),
    ).toBeVisible();
  });

  // Un appareil dont les envois sont refusés est visible dès le premier refus, avec sa cause.
  test("notifications_appareilEnEchec_afficheLeRefusEtSaCause", async ({
    page,
  }) => {
    await servirPush(page, [
      {
        endpoint: "https://fcm.googleapis.com/fcm/send/appareil-en-echec",
        creeLe: "2026-10-01T10:00:00Z",
        appareil: "Chrome sur Android",
        enEchecDepuis: "2026-10-16T09:00:00Z",
        cause:
          "le service de notification de votre navigateur a refusé les notifications à plusieurs reprises",
      },
    ]);
    await servirConfigNotifications(page, {
      frequenceNotifications: "quotidien",
    });
    await page.goto("/parametres#notifications");
    const section = page.locator("section#notifications");

    await expect(section.getByText("Chrome sur Android")).toBeVisible();
    await expect(section.getByText("Envois refusés")).toBeVisible();
    await expect(
      section.getByText("a refusé les notifications", { exact: false }),
    ).toBeVisible();
    await expect(
      section.getByRole("button", { name: "Révoquer" }),
    ).toBeVisible();
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
    await dialog.getByRole("button", { name: "Annuler" }).click();
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
    await dialog.getByRole("button", { name: "Annuler" }).click();
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
      await page
        .locator("form", { has: page.locator("input#identifiant") })
        .locator("button[type='submit']")
        .click();
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
