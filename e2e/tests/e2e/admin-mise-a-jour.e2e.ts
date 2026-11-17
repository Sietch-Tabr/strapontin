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
