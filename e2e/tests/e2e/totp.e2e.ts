import { test, expect, type APIRequestContext } from "@playwright/test";
import crypto from "node:crypto";
import { FICHIER_AUTH_ADMIN } from "./global-setup";
import {
  creerCompteJetableEtSeConnecter,
  supprimerUtilisateurParEmail,
} from "./helpers/admin.js";

const USER = "e2euser";
const MDP = process.env["E2E_USER_MOT_DE_PASSE"] ?? "e2e-user-2026";

// GET /api/admin/utilisateurs est paginé (50/page) — parcourt les pages jusqu'à trouver le
// username plutôt que de ne vérifier que la première.
async function trouverUtilisateurParUsername(
  request: APIRequestContext,
  username: string,
): Promise<{ id: string; username: string } | undefined> {
  for (let page = 0; ; page++) {
    const liste = await request.get(`/api/admin/utilisateurs?page=${page}`);
    const { utilisateurs: users, totalPages } = await liste.json();
    const u = users.find((x: { username: string }) => x.username === username);
    if (u) return u;
    if (page >= (totalPages ?? 1) - 1) return undefined;
  }
}

function base32decode(s: string): Buffer {
  const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = 0;
  let val = 0;
  const out: number[] = [];
  for (const c of s.toUpperCase().replace(/[^A-Z2-7]/g, "")) {
    val = (val << 5) | ALPHABET.indexOf(c);
    bits += 5;
    if (bits >= 8) {
      out.push((val >> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

function calculerCode(secret: string): string {
  const key = base32decode(secret);
  const compteur = Math.floor(Date.now() / 1000 / 30);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(compteur));
  const h = crypto.createHmac("sha1", key).update(buf).digest();
  const o = h[h.length - 1]! & 0xf;
  const n =
    (((h[o]! & 0x7f) << 24) |
      ((h[o + 1]! & 0xff) << 16) |
      ((h[o + 2]! & 0xff) << 8) |
      (h[o + 3]! & 0xff)) %
    1000000;
  return String(n).padStart(6, "0");
}

/** Attend le début de la prochaine fenêtre de 30 s pour éviter le rejeu d'un code déjà consommé. */
async function attendreProchaineFenetre(): Promise<void> {
  const ms = 30000 - (Date.now() % 30000) + 1000;
  await new Promise((r) => setTimeout(r, ms));
}

// Login répété : restreint à chromium pour ne pas épuiser le rate-limiter bucket4j.
test.describe("Scénario: flux TOTP complet (enrollment + challenge)", () => {
  test.describe.configure({ timeout: 90000 });

  test("activation_deconnexion_reconnexion_avec_challenge", async ({
    browser,
    browserName,
    isMobile,
  }) => {
    test.skip(
      browserName !== "chromium" || isMobile,
      "login répété — chromium desktop uniquement",
    );

    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    let secret = "";
    try {
      // 1. Login UI (compte sans TOTP) → doit arriver authentifié (fix chargerSession après login).
      await page.goto("/login");
      await page.fill("input[name='identifiant']", USER);
      await page.fill("input[name='motDePasse']", MDP);
      await page.click("button[type='submit']");
      await expect(page).not.toHaveURL(/login/, { timeout: 10000 });

      // 2. Enrollment TOTP via API : récupère le secret puis confirme avec un code valide.
      const setup = await page.request.get("/api/totp/setup");
      expect(setup.status()).toBe(200);
      secret = (await setup.json()).totpSecret;
      const confirm = await page.request.post("/api/totp/confirmer", {
        data: { code: calculerCode(secret) },
      });
      expect(confirm.status()).toBe(200);

      // 3. Déconnexion.
      await page.request.post("/api/auth/logout");
      await ctx.clearCookies();

      // Fenêtre suivante : le code de confirmation a été consommé (anti-rejeu).
      await attendreProchaineFenetre();

      // 4. Reconnexion UI → challenge TOTP → doit arriver authentifié
      //    (fix chargerSession après le challenge : sans lui, renvoi vers /login).
      await page.goto("/login");
      await page.fill("input[name='identifiant']", USER);
      await page.fill("input[name='motDePasse']", MDP);
      await page.click("button[type='submit']");
      await expect(page).toHaveURL(/auth\/totp/, { timeout: 10000 });
      await page.fill("#code", calculerCode(secret));
      await page.click("button[type='submit']");

      await expect(page).not.toHaveURL(/login|auth\/totp/, { timeout: 10000 });
      await expect(page.locator("main")).toBeVisible();
    } finally {
      // 5. Cleanup robuste : un admin réinitialise la 2FA de e2euser (sans code TOTP).
      //    Inconditionnel (pas de garde sur `secret`) : si /api/totp/setup échoue parce que
      //    le compte a déjà une 2FA active (résidu d'un run précédent interrompu avant son
      //    propre cleanup), `secret` reste vide et un cleanup gardé par `if (secret)` ne se
      //    déclencherait jamais — l'échec s'auto-perpétuerait indéfiniment sur tous les runs
      //    suivants au lieu de se corriger de lui-même dès la prochaine exécution.
      const adminCtx = await browser.newContext({
        storageState: FICHIER_AUTH_ADMIN,
      });
      try {
        const u = await trouverUtilisateurParUsername(adminCtx.request, USER);
        if (u) {
          await adminCtx.request.post(
            `/api/admin/utilisateurs/${u.id}/reset-totp`,
          );
        }
      } catch {
        /* best-effort */
      }
      await adminCtx.close();
      await ctx.close();
    }
  });

  test("blocage_apres_5_echecs_puis_deblocage_admin_et_reconnexion", async ({
    browser,
    browserName,
    isMobile,
  }) => {
    test.skip(
      browserName !== "chromium" || isMobile,
      "login répété — chromium desktop uniquement",
    );

    const ctx = await browser.newContext({ storageState: undefined });
    const page = await ctx.newPage();
    let secret = "";
    try {
      // 1. Login + enrollment TOTP (comme le cycle propre ci-dessus).
      await page.goto("/login");
      await page.fill("input[name='identifiant']", USER);
      await page.fill("input[name='motDePasse']", MDP);
      await page.click("button[type='submit']");
      await expect(page).not.toHaveURL(/login/, { timeout: 10000 });

      const setup = await page.request.get("/api/totp/setup");
      secret = (await setup.json()).totpSecret;
      const confirm = await page.request.post("/api/totp/confirmer", {
        data: { code: calculerCode(secret) },
      });
      expect(confirm.status()).toBe(200);

      await page.request.post("/api/auth/logout");
      await ctx.clearCookies();
      await attendreProchaineFenetre();

      // 2. Reconnexion → challenge TOTP en attente.
      await page.goto("/login");
      await page.fill("input[name='identifiant']", USER);
      await page.fill("input[name='motDePasse']", MDP);
      await page.click("button[type='submit']");
      await expect(page).toHaveURL(/auth\/totp/, { timeout: 10000 });

      // 3. 5 échecs consécutifs avec un code délibérément incorrect → verrouillage.
      const codeValide = calculerCode(secret);
      const codeFaux = codeValide === "000000" ? "000001" : "000000";
      for (let essai = 1; essai <= 4; essai++) {
        const rep = await page.request.post("/api/auth/totp", {
          data: { code: codeFaux },
        });
        expect(rep.status(), `échec ${essai}/4 doit être 422`).toBe(422);
      }
      const repVerrouillage = await page.request.post("/api/auth/totp", {
        data: { code: codeFaux },
      });
      expect(repVerrouillage.status()).toBe(401);
      expect((await repVerrouillage.json()).erreur).toContain("verrouillé");

      // 4. Le verrouillage est persistant en base : une nouvelle tentative de connexion
      //    échoue immédiatement en 401, même avec le code TOTP correct.
      await ctx.clearCookies();
      await page.goto("/login");
      await page.fill("input[name='identifiant']", USER);
      await page.fill("input[name='motDePasse']", MDP);
      await page.click("button[type='submit']");
      await expect(page).toHaveURL(/auth\/totp/, { timeout: 10000 });
      const repApresVerrouillage = await page.request.post("/api/auth/totp", {
        data: { code: calculerCode(secret) },
      });
      expect(repApresVerrouillage.status()).toBe(401);

      // 5. Un admin réinitialise la 2FA — le compte redevient accessible sans code.
      const adminCtx = await browser.newContext({
        storageState: FICHIER_AUTH_ADMIN,
      });
      const u = await trouverUtilisateurParUsername(adminCtx.request, USER);
      expect(u).toBeDefined();
      const resetRep = await adminCtx.request.post(
        `/api/admin/utilisateurs/${u!.id}/reset-totp`,
      );
      expect(resetRep.status()).toBe(200);
      await adminCtx.close();
      secret = ""; // la 2FA est désormais désactivée, plus besoin du cleanup du finally.

      // 6. Reconnexion sans code TOTP requis.
      await ctx.clearCookies();
      await page.goto("/login");
      await page.fill("input[name='identifiant']", USER);
      await page.fill("input[name='motDePasse']", MDP);
      await page.click("button[type='submit']");
      await expect(page).not.toHaveURL(/login|auth\/totp/, { timeout: 10000 });
      await expect(page.locator("main")).toBeVisible();
    } finally {
      // Cleanup inconditionnel — même raison que le test précédent : sans lui, un run interrompu
      // avant d'atteindre ce point laisse le compte partagé bloqué en 2FA active pour tous les
      // runs suivants, sans rien pour se corriger de lui-même.
      const adminCtx = await browser.newContext({
        storageState: FICHIER_AUTH_ADMIN,
      });
      try {
        const u = await trouverUtilisateurParUsername(adminCtx.request, USER);
        if (u) {
          await adminCtx.request.post(
            `/api/admin/utilisateurs/${u.id}/reset-totp`,
          );
        }
      } catch {
        /* best-effort */
      }
      await adminCtx.close();
      await ctx.close();
    }
  });

  // Compte jetable : l'état du TOTP ne dépend ni de e2euser ni de l'ordre des scénarios.
  test("desactivation_codeIncorrectPuisCodeValide_retireLeTotp", async ({
    browser,
    browserName,
    isMobile,
  }) => {
    test.skip(
      browserName !== "chromium" || isMobile,
      "compte créé, attente d'une fenêtre TOTP — chromium desktop uniquement",
    );

    const { page, ctx, email } = await creerCompteJetableEtSeConnecter(
      browser,
      "e2etotpdes",
    );
    try {
      const setup = await page.request.get("/api/totp/setup");
      expect(setup.status()).toBe(200);
      const secret = (await setup.json()).totpSecret as string;
      const confirmation = await page.request.post("/api/totp/confirmer", {
        data: { code: calculerCode(secret) },
      });
      expect(confirmation.status()).toBe(200);
      // Le code de confirmation est consommé : la désactivation attend la fenêtre suivante.
      await attendreProchaineFenetre();

      await page.goto("/parametres#compte");
      const champ = page.locator("input#totp-desact-code");
      await expect(champ).toBeVisible();
      expect((await champ.boundingBox())!.width).toBeLessThan(200);
      await expect(champ).not.toHaveAttribute("placeholder", /.+/);
      await expect(page.locator("#aide-totp-desact")).toContainText(
        "application d'authentification",
      );

      const codeValide = calculerCode(secret);
      const codeFaux = codeValide === "123456" ? "654321" : "123456";
      await champ.fill(codeFaux);
      await page.getByRole("button", { name: "Désactiver le TOTP" }).click();
      await expect(page.locator(".alerte-erreur").first()).toBeVisible();
      await expect(champ).toBeVisible();

      await champ.fill(calculerCode(secret));
      await page.getByRole("button", { name: "Désactiver le TOTP" }).click();
      await expect(
        page
          .locator(".alerte-succes")
          .filter({ hasText: "Authentification à deux facteurs désactivée" }),
      ).toBeVisible();
      await expect(champ).toHaveCount(0);
      await expect(
        page.getByRole("link", { name: "Configurer le TOTP" }),
      ).toBeVisible();
    } finally {
      await ctx.close();
      await supprimerUtilisateurParEmail(email);
    }
  });
});
