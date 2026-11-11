import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { test, expect, type APIRequestContext } from "@playwright/test";
import { FICHIER_AUTH_ADMIN, FICHIER_AUTH_USER } from "./global-setup.js";
import {
  BASE_URL,
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

const LXC_DEV_HOTE = "root@192.168.20.78";

// Les jetons ne sont stockés que hachés (SHA-256) : aucun endpoint ne rend le lien d'une invitation
// d'utilisateur. Sur le dev uniquement, le jeton d'une invitation éphémère est remplacé par un
// jeton connu, comme le fait invitation.e2e.ts pour antidater une expiration.
function poserJetonConnu(email: string, jeton: string): void {
  const hash = createHash("sha256").update(jeton, "utf8").digest("hex");
  const sql = `UPDATE invitations_utilisateurs SET token_hash = '${hash}' WHERE email = '${email}' AND active_le IS NULL AND annulee_le IS NULL;`;
  execFileSync("ssh", [LXC_DEV_HOTE, `su postgres -c "psql -d strapontin"`], {
    input: sql,
    timeout: 15000,
  });
}

async function trouverUtilisateurParEmail(
  ctx: APIRequestContext,
  email: string,
): Promise<{ id: string; parrain: string | null }> {
  for (let page = 0; ; page++) {
    const rep = await ctx.get(`/api/admin/utilisateurs?page=${page}`);
    expect(rep.ok()).toBe(true);
    const { utilisateurs, totalPages } = await rep.json();
    const cible = utilisateurs.find(
      (u: { email: string }) => u.email === email,
    );
    if (cible) return cible;
    if (page >= (totalPages ?? 1) - 1) throw new Error(`${email} introuvable`);
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

    test("invite_activerLeLien_creeLeCompteAvecSonParrainEtLiberelaPlace", async ({
      page,
      browser,
    }) => {
      const suffixe = Date.now();
      const email = `e2e-invite-${suffixe}@test.local`;
      const jeton = `jeton-e2e-invite-${suffixe}-abcdefghijklmnopqrstuvwxyz`;
      try {
        await page.goto("/parametres#inviter");
        const section = page.locator("section#inviter");
        await section.getByLabel("Adresse email").fill(email);
        await section
          .getByRole("button", { name: "Envoyer l'invitation" })
          .click();
        await expect(section).toContainText(email);
        await expect(section).toContainText("En attente");

        poserJetonConnu(email, jeton);
        const ctxInvite = await browser.newContext({ storageState: undefined });
        const pageInvite = await ctxInvite.newPage();
        await pageInvite.goto(`${BASE_URL}/reset-password?token=${jeton}`);
        await pageInvite.fill(
          'input[name="identifiant"]',
          `e2einv${suffixe}`.slice(0, 30),
        );
        await pageInvite.fill('input[name="motDePasse"]', "TestE2E!2026invite");
        await pageInvite.click('button[type="submit"]');
        await pageInvite.waitForURL(
          (url) => !url.pathname.startsWith("/reset"),
        );
        await ctxInvite.close();

        // Côté parrain : l'invitation est activée et sa place est libérée.
        await page.reload();
        await expect(section).toContainText("Activée");
        await expect(section.getByLabel("Adresse email")).toBeEnabled();

        // Côté administration : le compte porte son parrain.
        const compte = await trouverUtilisateurParEmail(admin, email);
        expect(compte.parrain).toBe(E2E_USER_IDENTIFIANT);
      } finally {
        await supprimerUtilisateurParEmail(email);
      }
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
