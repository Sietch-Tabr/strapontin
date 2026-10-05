import { test, expect, type Page } from "@playwright/test";

// La carte de signalement dépend de la configuration chargée après l'affichage : un des
// trois canaux est visible une fois celle-ci arrivée.
async function attendreCanalDeContact(page: Page) {
  await expect(
    page
      .locator("#btn-ouvrir-signalement")
      .or(page.locator("a[href^='mailto:']"))
      .or(page.getByText("Aucun moyen de contact configuré actuellement."))
      .first(),
  ).toBeVisible();
}

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
    const bouton = page.locator("#btn-ouvrir-signalement");
    await attendreCanalDeContact(page);
    if (!(await bouton.isVisible())) {
      // Resend non configuré : soit un lien mailto (CONTACT_EMAIL configuré), soit le message
      // de repli #842 si aucun canal de contact n'est configuré du tout.
      const lien = page.locator("a[href^='mailto:']");
      if (await lien.count()) {
        // #842 : le sélecteur href^='mailto:' matcherait aussi un mailto: vide sans adresse —
        // vérifier le contenu réel.
        await expect(lien).toHaveAttribute("href", /^mailto:.+@.+/);
      } else {
        await expect(
          page.getByText("Aucun moyen de contact configuré actuellement."),
        ).toBeVisible();
      }
      return;
    }
    await bouton.click();
    await expect(page.locator("#form-signalement")).toBeVisible();
  });

  // #821 : le test ci-dessus n'exerce que l'ouverture du dialog, jamais une soumission réelle —
  // le bug (message de succès jamais peint car le dialog se ferme dans la même fonction
  // synchrone) n'était donc jamais détecté. Restreint à chromium (bucket4j 3 signalements/heure
  // partagé par IP — CLAUDE.md, éviter d'épuiser le quota sur plusieurs navigateurs).
  test("aide_signalement_soumissionReussie_afficheAlerteAvantFermeture", async ({
    page,
  }, testInfo) => {
    test.skip(
      testInfo.project.name !== "chromium",
      "quota de signalements partagé par IP",
    );
    await page.goto("/aide");
    const bouton = page.locator("#btn-ouvrir-signalement");
    await attendreCanalDeContact(page);
    test.skip(
      !(await bouton.isVisible()),
      "Resend non configuré sur cet environnement",
    );
    await bouton.click();
    const dialog = page.locator("#dialog-signaler");
    await expect(dialog).toBeVisible();

    await page.locator("#message-signalement").fill("Signalement e2e #821");
    await page.locator("#form-signalement button[type='submit']").click();

    await expect(
      dialog.locator(".alerte-succes", { hasText: "Signalement envoyé" }),
    ).toBeVisible({ timeout: 2000 });
    // Le dialog doit rester ouvert le temps que l'alerte soit visible (délai de fermeture #821),
    // puis se fermer de lui-même.
    await expect(dialog).not.toBeVisible({ timeout: 3000 });
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
      await expect(
        page.locator(".barre-laterale a[href='/changelog']"),
      ).toBeVisible();
    }
  });

  test("changelog_contenu_visible", async ({ page }) => {
    await page.goto("/changelog");
    await expect(page.locator("main")).toBeVisible();
    await expect(page.locator("main")).not.toBeEmpty();
  });

  // #820 : le mécanisme "changelog lu" était entièrement mort (VERSION_COURANTE figée,
  // changelog_version_vue jamais relue) — pilote désormais un badge "nouveautés", vérifié ici
  // via l'API plutôt que le badge visuel (dépend de l'historique déjà vu de e2euser, non
  // déterministe entre les runs).
  test("api_changelog_nouveaute_retourneBooleenEtSeDesactiveApresMarquage", async ({
    page,
  }) => {
    const repAvant = await page.request.get("/api/changelog/nouveaute");
    expect(repAvant.ok()).toBeTruthy();
    const corpsAvant = await repAvant.json();
    expect(typeof corpsAvant.nonVu).toBe("boolean");

    const repMarquer = await page.request.post("/api/changelog/marquer-vue");
    expect(repMarquer.status()).toBe(204);

    const repApres = await page.request.get("/api/changelog/nouveaute");
    const corpsApres = await repApres.json();
    expect(corpsApres.nonVu).toBe(false);
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
      page.getByRole("link", { name: "Paramètres → Données" }),
    ).toHaveAttribute("href", "/parametres#donnees");
  });

  test("aide_donneesPersonnelles_annonceLaSuppressionPourInactivite", async ({
    page,
  }) => {
    await page.goto("/aide");
    await expect(page.locator("body")).toContainText(
      "Un compte inutilisé pendant 6 mois",
    );
  });
});
