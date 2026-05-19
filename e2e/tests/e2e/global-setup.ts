import { chromium } from "@playwright/test";
import { existsSync, readFileSync } from "fs";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";
export const FICHIER_STATE = "tests/e2e/.auth-state.json";

const E2E_IDENTIFIANT = process.env["E2E_IDENTIFIANT"] ?? "";
const E2E_MOT_DE_PASSE = process.env["E2E_MOT_DE_PASSE"] ?? "";

export default async function setup(): Promise<() => Promise<void>> {
  if (!E2E_IDENTIFIANT && existsSync(FICHIER_STATE)) {
    const etat = JSON.parse(readFileSync(FICHIER_STATE, "utf-8")) as {
      cookies?: { name: string; value: string; expires?: number }[];
    };
    const maintenant = Date.now() / 1000;
    const sessionExistante = etat.cookies?.find(
      (c) =>
        c.name === "SESSION" &&
        c.value &&
        (!c.expires || c.expires < 0 || c.expires > maintenant),
    );
    if (sessionExistante) {
      console.log("[e2e setup] session existante réutilisée");
      return async () => {};
    }
  }

  if (!E2E_IDENTIFIANT || !E2E_MOT_DE_PASSE) {
    throw new Error(
      "Variables d'environnement E2E_IDENTIFIANT et E2E_MOT_DE_PASSE requises.\n" +
        "Exemple : E2E_IDENTIFIANT=kwaite E2E_MOT_DE_PASSE=... pnpm test",
    );
  }

  const navigateur = await chromium.launch();
  const contexte = await navigateur.newContext({ baseURL: BASE_URL });
  const page = await contexte.newPage();

  await page.goto("/login");
  await page.fill('input[name="identifiant"]', E2E_IDENTIFIANT);
  await page.fill('input[name="motDePasse"]', E2E_MOT_DE_PASSE);
  await Promise.all([
    page.waitForURL(/\/(?!login)/, { waitUntil: "domcontentloaded" }),
    page.click('button[type="submit"]'),
  ]);

  if (page.url().includes("/login")) {
    await navigateur.close();
    throw new Error(
      `Échec du login E2E (identifiant ou mot de passe incorrect) sur ${BASE_URL}.`,
    );
  }

  await contexte.storageState({ path: FICHIER_STATE });
  await navigateur.close();

  console.log("[e2e setup] session Playwright obtenue via form login");
  return async () => {};
}
