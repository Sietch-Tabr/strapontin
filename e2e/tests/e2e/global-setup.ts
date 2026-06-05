import { request } from "@playwright/test";
import { existsSync, readFileSync } from "fs";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";
export const FICHIER_STATE = "tests/e2e/.auth-state.json";

const E2E_IDENTIFIANT = process.env["E2E_IDENTIFIANT"] ?? "";
const E2E_MOT_DE_PASSE = process.env["E2E_MOT_DE_PASSE"] ?? "";

export default async function setup(): Promise<() => Promise<void>> {
  if (existsSync(FICHIER_STATE)) {
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

  const contexte = await request.newContext({ baseURL: BASE_URL });

  const rep = await contexte.post("/api/auth/login", {
    data: { identifiant: E2E_IDENTIFIANT, motDePasse: E2E_MOT_DE_PASSE },
  });

  if (!rep.ok()) {
    await contexte.dispose();
    throw new Error(
      `Échec du login E2E (HTTP ${rep.status()}) sur ${BASE_URL}.`,
    );
  }

  await contexte.storageState({ path: FICHIER_STATE });
  await contexte.dispose();

  console.log("[e2e setup] session Playwright obtenue via API /api/auth/login");
  return async () => {};
}
