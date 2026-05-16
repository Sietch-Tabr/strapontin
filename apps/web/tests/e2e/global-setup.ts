import { writeFileSync, existsSync, readFileSync } from "fs";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:3000";
export const FICHIER_STATE = "tests/e2e/.auth-state.json";

const E2E_IDENTIFIANT = process.env["E2E_IDENTIFIANT"] ?? "";
const E2E_MOT_DE_PASSE = process.env["E2E_MOT_DE_PASSE"] ?? "";

export default async function setup(): Promise<() => Promise<void>> {
  // Si le fichier d'état existe déjà avec une session SESSION valide, l'utiliser directement.
  if (!E2E_IDENTIFIANT && existsSync(FICHIER_STATE)) {
    const etat = JSON.parse(readFileSync(FICHIER_STATE, "utf-8")) as {
      cookies?: { name: string; value: string }[];
    };
    const sessionExistante = etat.cookies?.find(
      (c) => c.name === "SESSION" && c.value,
    );
    if (sessionExistante) {
      console.log("[e2e setup] session existante réutilisée");
      return async () => {};
    }
  }

  if (!E2E_IDENTIFIANT || !E2E_MOT_DE_PASSE) {
    throw new Error(
      "Variables d'environnement E2E_IDENTIFIANT et E2E_MOT_DE_PASSE requises.\n" +
        "Exemple : E2E_IDENTIFIANT=kwaite E2E_MOT_DE_PASSE=... pnpm test:e2e",
    );
  }

  const cookie = await seConnecter();
  if (!cookie) {
    throw new Error(
      `Impossible d'obtenir une session E2E sur ${BASE_URL}.\n` +
        "Vérifier les credentials et que l'app dev est accessible.",
    );
  }

  const hostname = new URL(BASE_URL).hostname;

  writeFileSync(
    FICHIER_STATE,
    JSON.stringify({
      cookies: [
        {
          name: "SESSION",
          value: cookie,
          domain: hostname,
          path: "/",
          httpOnly: true,
          secure: false,
          sameSite: "Lax",
          expires: Math.floor(Date.now() / 1000) + 604800,
        },
      ],
      origins: [],
    }),
  );

  console.log("[e2e setup] session Java obtenue");
  return async () => {};
}

async function seConnecter(): Promise<string | null> {
  try {
    const rep = await fetch(`${BASE_URL}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        identifiant: E2E_IDENTIFIANT,
        motDePasse: E2E_MOT_DE_PASSE,
      }),
      signal: AbortSignal.timeout(10000),
    });

    if (!rep.ok) return null;

    const setCookie = rep.headers.get("set-cookie") ?? "";
    const match = setCookie.match(/SESSION=([^;]+)/);
    return match?.[1] ?? null;
  } catch {
    return null;
  }
}
