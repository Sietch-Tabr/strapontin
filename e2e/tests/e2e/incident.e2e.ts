import { test, expect } from "@playwright/test";
import { creerContexteAdmin, nettoyer } from "./helpers/admin.js";

// La communication d'incident est un état global (une seule active, affichée à tous) : un seul
// navigateur la publie, sinon les cinq projets se la retireraient mutuellement. Aucun email,
// push ni changement de mot de passe forcé : ces canaux toucheraient les vrais comptes du dev.
test.describe("Communication d'incident", () => {
  test.afterEach(async () => {
    const ctx = await creerContexteAdmin();
    await nettoyer(
      "fin de la communication",
      ctx.post("/api/admin/incident/terminer"),
    );
    await ctx.dispose();
  });

  test("incident_publie_visibleSurLaConnexionSansSession_puisMasqueUneFoisLu", async ({
    browser,
  }, testInfo) => {
    test.skip(testInfo.project.name !== "chromium");
    const admin = await creerContexteAdmin();
    const titre = `Incident E2E ${Date.now()}`;
    const repPublication = await admin.post("/api/admin/incident", {
      data: {
        titre,
        message: "Message de test.",
        email: false,
        push: false,
        changementMdpForce: false,
      },
    });
    expect(repPublication.status()).toBe(200);
    await admin.dispose();

    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    await page.goto("/login");
    const banniere = page.locator(".banniere-incident");
    await expect(banniere).toContainText(titre, { timeout: 5000 });

    await banniere.getByRole("button", { name: "J'ai compris" }).click();
    await expect(banniere).not.toBeVisible();
    await page.reload();
    await expect(page.locator(".banniere-incident")).not.toBeVisible();
    await ctx.close();
  });

  test("api_incident_sansCommunicationActive_retourne204", async ({
    browser,
  }, testInfo) => {
    test.skip(testInfo.project.name !== "chromium");
    const admin = await creerContexteAdmin();
    await nettoyer(
      "fin de la communication",
      admin.post("/api/admin/incident/terminer"),
    );
    await admin.dispose();
    const ctx = await browser.newContext({ storageState: undefined });

    const rep = await ctx.request.get("/api/incident");

    expect(rep.status()).toBe(204);
    await ctx.close();
  });

  test("api_admin_incident_titreVide_retourne400", async () => {
    const admin = await creerContexteAdmin();
    const rep = await admin.post("/api/admin/incident", {
      data: { titre: "", message: "x", email: false, push: false },
    });
    expect(rep.status()).toBe(400);
    await admin.dispose();
  });
});
