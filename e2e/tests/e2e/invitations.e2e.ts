import { test, expect, type APIRequestContext } from "@playwright/test";
import { FICHIER_AUTH_ADMIN, FICHIER_AUTH_USER } from "./global-setup.js";
import {
  creerContexteAdmin,
  naviguerVersUtilisateurs,
  supprimerUtilisateurParEmail,
} from "./helpers/admin.js";

// Adresse de test de Resend : la livraison réussit sans rebond ni boîte réelle.
const EMAIL_INVITE = "delivered@resend.dev";
const E2E_USER_IDENTIFIANT = "e2euser";

async function idUtilisateur(
  ctx: APIRequestContext,
  identifiant: string,
): Promise<string> {
  for (let page = 0; ; page++) {
    const rep = await ctx.get(`/api/admin/utilisateurs?page=${page}`);
    expect(rep.ok()).toBe(true);
    const { utilisateurs, totalPages } = await rep.json();
    const cible = utilisateurs.find(
      (u: { username: string }) => u.username === identifiant,
    );
    if (cible) return cible.id;
    if (page >= (totalPages ?? 1) - 1) throw new Error("e2euser introuvable");
  }
}

test.describe("Scénario: invitations par un utilisateur", () => {
  // Cadence de 5 envois par heure et par utilisateur : un seul envoi par exécution, sur un seul
  // navigateur. Le renvoi et l'activation sont couverts par les tests Java.
  test.skip(
    ({ browserName, isMobile }) => browserName !== "chromium" || isMobile,
    "Cadence d'envoi : testé sur chromium desktop uniquement",
  );

  test.describe("session utilisateur", () => {
    test.use({ storageState: FICHIER_AUTH_USER });

    let admin: APIRequestContext;
    let userId: string;

    test.beforeAll(async () => {
      admin = await creerContexteAdmin();
      userId = await idUtilisateur(admin, E2E_USER_IDENTIFIANT);
      // Plafond d'une seule invitation : atteint dès le premier envoi, sans second envoi.
      const rep = await admin.put(
        `/api/admin/utilisateurs/${userId}/plafond-invitations`,
        { data: { plafond: 1 } },
      );
      expect(rep.ok()).toBe(true);
    });

    test.afterAll(async () => {
      await admin.put(`/api/admin/utilisateurs/${userId}/plafond-invitations`, {
        data: { plafond: null },
      });
      await admin.dispose();
    });

    test.afterEach(async ({ request }) => {
      const rep = await request.get("/api/invitations");
      if (!rep.ok()) return;
      const { invitations } = await rep.json();
      for (const i of invitations) {
        if (i.statut === "en_attente")
          await request.delete(`/api/invitations/${i.id}`);
      }
    });

    test("inviter_plafondAtteint_annoncePuisAnnulerLibereLeCreneau", async ({
      page,
    }) => {
      await page.goto("/parametres#inviter");
      const section = page.locator("section#inviter");
      const champ = section.getByLabel("Adresse email");

      await expect(section).toContainText("Il vous reste 1 invitation");
      await champ.fill(EMAIL_INVITE);
      await section
        .getByRole("button", { name: "Envoyer l'invitation" })
        .click();

      await expect(page.locator(".alerte-succes").first()).toContainText(
        "Invitation envoyée",
      );
      await expect(section).toContainText(EMAIL_INVITE);
      await expect(section).toContainText("En attente");
      // Le plafond est lisible avant la saisie, le champ est désactivé.
      await expect(section).toContainText("nombre maximal d'invitations");
      await expect(champ).toBeDisabled();

      await section
        .getByRole("button", { name: `Annuler l'invitation à ${EMAIL_INVITE}` })
        .click();

      await expect(section).toContainText("Annulée");
      await expect(section).toContainText("Il vous reste 1 invitation");
      await expect(champ).toBeEnabled();
    });

    test("inviter_renvoyer_confirmeEtGardeLInvitationEnAttente", async ({
      page,
    }) => {
      await page.goto("/parametres#inviter");
      const section = page.locator("section#inviter");

      await section.getByLabel("Adresse email").fill(EMAIL_INVITE);
      await section
        .getByRole("button", { name: "Envoyer l'invitation" })
        .click();
      await expect(section).toContainText(EMAIL_INVITE);

      await section
        .getByRole("button", {
          name: `Renvoyer l'invitation à ${EMAIL_INVITE}`,
        })
        .click();

      await expect(
        page
          .locator(".alerte-succes")
          .filter({ hasText: "Invitation renvoyée" }),
      ).toBeVisible();
      await expect(section).toContainText("En attente");
      await expect(
        section.getByRole("button", {
          name: `Annuler l'invitation à ${EMAIL_INVITE}`,
        }),
      ).toBeVisible();
    });

    test("inviter_adresseInvalide_neSoumetPasLeFormulaire", async ({
      page,
    }) => {
      await page.goto("/parametres#inviter");
      const section = page.locator("section#inviter");

      const requetes: string[] = [];
      page.on("request", (requete) => {
        if (
          requete.method() === "POST" &&
          requete.url().includes("/api/invitations")
        )
          requetes.push(requete.url());
      });

      const champ = section.getByLabel("Adresse email");
      await champ.fill("pas-une-adresse");
      await section
        .getByRole("button", { name: "Envoyer l'invitation" })
        .click();

      // Validation native du navigateur : le formulaire n'est pas soumis, aucune invitation créée.
      expect(
        await champ.evaluate((e) => (e as HTMLInputElement).validity.valid),
      ).toBe(false);
      expect(requetes).toEqual([]);
    });
  });

  test.describe("session administrateur", () => {
    test.use({ storageState: FICHIER_AUTH_ADMIN });

    test("admin_plafondParDefaut_estAffichePuisEnregistrable", async ({
      page,
      isMobile,
    }) => {
      await page.goto("/admin");
      await naviguerVersUtilisateurs(page, isMobile);
      const section = page.locator("section#utilisateurs");
      const champ = section.getByLabel("Plafond par défaut");

      await expect(champ).toBeVisible();
      const valeur = await champ.inputValue();
      await champ.fill(valeur);
      await section
        .getByRole("button", { name: "Enregistrer", exact: true })
        .click();

      await expect(page.locator(".alerte-succes").first()).toContainText(
        "Plafond par défaut enregistré",
      );
    });

    test("admin_plafondDUnCompte_enregistrePuisRevientAuDefaut", async ({
      page,
      isMobile,
    }) => {
      const email = `e2e-plafond-${Date.now()}@example.com`;
      try {
        await page.goto("/admin");
        await naviguerVersUtilisateurs(page, isMobile);
        await page.fill('.form-creer-utilisateur input[type="email"]', email);
        await page.click('.form-creer-utilisateur button[type="submit"]');
        const dialogInvitation = page.locator("dialog#dialog-invitation");
        await expect(dialogInvitation).toBeVisible({ timeout: 5000 });
        await page.click("button#btn-fermer-invitation");
        await expect(dialogInvitation).not.toBeVisible();

        const carte = page.locator(".user-carte").filter({ hasText: email });
        const champ = carte.getByLabel(/Invitations en attente/);
        const revenir = carte.getByRole("button", {
          name: "Revenir au défaut",
        });
        await expect(champ).toHaveValue("");
        await expect(revenir).toHaveCount(0);

        await champ.fill("4");
        await carte
          .getByRole("button", { name: "Enregistrer le plafond" })
          .click();
        await expect(
          page
            .locator(".alerte-succes")
            .filter({ hasText: "Plafond d'invitations enregistré" })
            .first(),
        ).toBeVisible();
        await expect(champ).toHaveValue("4");
        await expect(revenir).toBeVisible();

        await revenir.click();
        await expect(revenir).toHaveCount(0);
        await expect(champ).toHaveValue("");
      } finally {
        await supprimerUtilisateurParEmail(email);
      }
    });
  });
});
