import { request } from "@playwright/test";
import { existsSync, readFileSync } from "fs";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";

export const FICHIER_AUTH_ADMIN = "tests/e2e/.auth-admin.json";
export const FICHIER_AUTH_USER = "tests/e2e/.auth-user.json";

const E2E_ADMIN_IDENTIFIANT = "e2e-admin";
const E2E_ADMIN_MOT_DE_PASSE =
  process.env["E2E_ADMIN_MOT_DE_PASSE"] ?? "e2e-admin-2026";
const E2E_USER_IDENTIFIANT = "e2e-user";
const E2E_USER_MOT_DE_PASSE =
  process.env["E2E_USER_MOT_DE_PASSE"] ?? "e2e-user-2026";

function sessionValide(fichier: string): boolean {
  if (!existsSync(fichier)) return false;
  const etat = JSON.parse(readFileSync(fichier, "utf-8")) as {
    cookies?: { name: string; value: string; expires?: number }[];
  };
  const maintenant = Date.now() / 1000;
  return !!etat.cookies?.find(
    (c) =>
      c.name === "SESSION" &&
      c.value &&
      (!c.expires || c.expires < 0 || c.expires > maintenant),
  );
}

async function loginEtSauvegarder(
  baseURL: string,
  identifiant: string,
  motDePasse: string,
  fichier: string,
): Promise<void> {
  const contexte = await request.newContext({ baseURL });
  const rep = await contexte.post("/api/auth/login", {
    data: { identifiant, motDePasse },
  });
  if (!rep.ok()) {
    await contexte.dispose();
    throw new Error(
      `Échec du login E2E '${identifiant}' (HTTP ${rep.status()}) sur ${baseURL}.`,
    );
  }
  await contexte.storageState({ path: fichier });
  await contexte.dispose();
  console.log(`[e2e setup] session '${identifiant}' sauvegardée → ${fichier}`);
}

export default async function setup(): Promise<() => Promise<void>> {
  const adminValide = sessionValide(FICHIER_AUTH_ADMIN);
  const userValide = sessionValide(FICHIER_AUTH_USER);

  if (adminValide && userValide) {
    console.log("[e2e setup] sessions admin + user existantes réutilisées");
    return async () => {};
  }

  if (!adminValide) {
    await loginEtSauvegarder(
      BASE_URL,
      E2E_ADMIN_IDENTIFIANT,
      E2E_ADMIN_MOT_DE_PASSE,
      FICHIER_AUTH_ADMIN,
    );
  }

  if (!userValide) {
    await loginEtSauvegarder(
      BASE_URL,
      E2E_USER_IDENTIFIANT,
      E2E_USER_MOT_DE_PASSE,
      FICHIER_AUTH_USER,
    );
  }

  return async () => {};
}
