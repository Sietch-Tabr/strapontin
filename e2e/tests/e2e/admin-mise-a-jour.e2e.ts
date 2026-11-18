import { test, expect } from "@playwright/test";
import { FICHIER_AUTH_ADMIN, FICHIER_AUTH_USER } from "./global-setup.js";

// Chemins sans effet seulement : aucune installation n'est jamais demandée. Le compte e2eadmin n'a
// pas de TOTP, donc l'API refuse toute demande avant même de regarder la version.
test.describe("Scénario: mise à jour depuis l'administration", () => {
  test.describe("administrateur", () => {
    test.use({ storageState: FICHIER_AUTH_ADMIN });

    test("miseAJour_onglet_afficheLaVersionEnService", async ({ page }) => {
      await page.goto("/admin#mise-a-jour");
      await expect(page.locator("section#mise-a-jour")).toBeVisible();
      await expect(page.getByText("Version en service")).toBeVisible();
      await expect(page.locator("section#jobs")).not.toBeVisible();
    });

    const etatSimule = (resultat: string, message: string | null) => ({
      versionInstallee: "3.2.0",
      versionProposee: null,
      demandeEnAttente: false,
      enCours: false,
      totpActif: false,
      etat: {
        resultat,
        etape: "CONTROLE",
        versionCible: "3.2.0",
        debut: "2026-11-17T21:27:09Z",
        fin: "2026-11-17T21:27:39Z",
        message,
      },
    });

    test("miseAJour_reussiteRelue_afficheUneLigneNeutreSansBoutonNiEtapes", async ({
      page,
    }) => {
      await page.route("**/api/admin/mise-a-jour", (route) =>
        route.fulfill({ json: etatSimule("REUSSI", null) }),
      );

      await page.goto("/admin#mise-a-jour");

      await expect(
        page.getByText("Dernière mise à jour : version 3.2.0 installée le"),
      ).toBeVisible();
      await expect(page.getByText("Avancement")).toHaveCount(0);
      await expect(
        page.getByRole("button", { name: "Recharger l'application" }),
      ).toHaveCount(0);
    });

    test("miseAJour_echecRelu_gardeLeDetailDesEtapes", async ({ page }) => {
      await page.route("**/api/admin/mise-a-jour", (route) =>
        route.fulfill({
          json: etatSimule("ECHEC", "Le téléchargement a échoué"),
        }),
      );

      await page.goto("/admin#mise-a-jour");

      await expect(page.getByText("Avancement")).toBeVisible();
      await expect(
        page.getByText("La mise à jour a échoué : Le téléchargement a échoué."),
      ).toBeVisible();
      await expect(page.getByText("Dernière mise à jour :")).toHaveCount(0);
    });

    test("miseAJour_serveurSansReponse_afficheLeBandeauInjoignable", async ({
      page,
    }) => {
      await page.goto("/admin#mise-a-jour");
      await expect(page.locator("section#mise-a-jour")).toBeVisible();
      // Tout l'API est coupé, comme serveur arrêté : un seul appel en échec serait effacé par les
      // autres, qui réussissent.
      await page.route("**/api/**", (route) => route.abort());

      await page.reload({ waitUntil: "domcontentloaded" });

      await expect(page.getByText("Serveur injoignable")).toBeVisible({
        timeout: 10000,
      });
    });

    test("miseAJour_api_exposeLesVersionsEtLEtat", async ({ request }) => {
      const rep = await request.get("/api/admin/mise-a-jour");
      expect(rep.status()).toBe(200);
      const corps = await rep.json();
      expect(corps.versionInstallee).toMatch(/^\d+\.\d+\.\d+/);
      expect(typeof corps.demandeEnAttente).toBe("boolean");
      expect(typeof corps.enCours).toBe("boolean");
      expect(corps.totpActif).toBe(false);
    });

    test("miseAJour_demandeSansTotpActive_refuseeEn422", async ({
      request,
    }) => {
      const rep = await request.post("/api/admin/mise-a-jour", {
        data: { version: "9.9.9", motDePasse: "x", codeTotp: "123456" },
      });
      expect(rep.status()).toBe(422);
      expect((await rep.json()).erreur).toContain(
        "authentification à deux facteurs",
      );
    });

    test("miseAJour_corpsIncomplet_refuseEn400", async ({ request }) => {
      const rep = await request.post("/api/admin/mise-a-jour", { data: {} });
      expect(rep.status()).toBe(400);
    });
  });

  // Routes internes du dev (profil dev seulement) : sans le secret partagé, rien ne passe, même avec
  // une session administrateur.
  test.describe("API interne de mise à jour du dev", () => {
    test.use({ storageState: FICHIER_AUTH_ADMIN });

    test("miseAJour_apiInterne_refuseSansJeton", async ({ request }) => {
      const etat = await request.get("/api/interne/mise-a-jour-etat");
      expect(etat.status()).toBe(403);
      const demande = await request.post("/api/interne/mise-a-jour-demande");
      expect(demande.status()).toBe(403);
    });

    test("miseAJour_apiInterne_refuseUnMauvaisJeton", async ({ request }) => {
      const etat = await request.get("/api/interne/mise-a-jour-etat", {
        headers: { Authorization: "Bearer mauvais" },
      });
      expect(etat.status()).toBe(403);
    });
  });

  test.describe("utilisateur sans droit d'administration", () => {
    test.use({ storageState: FICHIER_AUTH_USER });

    test("miseAJour_utilisateurSimple_refuseEn403", async ({ request }) => {
      const lecture = await request.get("/api/admin/mise-a-jour");
      expect(lecture.status()).toBe(403);
      const demande = await request.post("/api/admin/mise-a-jour", {
        data: { version: "9.9.9", motDePasse: "x", codeTotp: "123456" },
      });
      expect(demande.status()).toBe(403);
    });
  });
});
