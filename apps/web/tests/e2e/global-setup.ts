import { writeFileSync } from "fs";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:3000";
export const FICHIER_STATE = "tests/e2e/.auth-state.json";

const E2E_EMAIL = "e2e@test.local";
const E2E_PASSWORD = "E2e-Test-2026!!";

export default async function setup(): Promise<() => Promise<void>> {
  // Tenter d'abord un login, créer le compte si inexistant
  let cookie = await seConnecter();
  console.log("[e2e setup] cookie obtenu:", cookie ? "oui" : "non");

  if (!cookie) {
    await creerCompte();
    cookie = await seConnecter();
    console.log("[e2e setup] cookie après création:", cookie ? "oui" : "non");
  }

  if (!cookie) {
    throw new Error(
      `Impossible d'obtenir une session E2E sur ${BASE_URL}.\n` +
        "Vérifier que l'app dev est accessible.",
    );
  }

  const hostname = new URL(BASE_URL).hostname;

  writeFileSync(
    FICHIER_STATE,
    JSON.stringify({
      cookies: [
        {
          name: "better-auth.session_token",
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

  return async () => {};
}

async function seConnecter(): Promise<string | null> {
  // Jusqu'à 3 tentatives avec backoff pour le rate limit Better Auth
  for (let tentative = 0; tentative < 3; tentative++) {
    try {
      const rep = await fetch(`${BASE_URL}/api/auth/sign-in/email`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Origin: BASE_URL,
        },
        body: JSON.stringify({ email: E2E_EMAIL, password: E2E_PASSWORD }),
        signal: AbortSignal.timeout(10000),
      });

      console.log("[e2e login] status:", rep.status, "headers:", Object.fromEntries(rep.headers.entries()));
      if (rep.status === 429) {
        const retryAfter = parseInt(rep.headers.get("x-retry-after") ?? "10");
        await new Promise((r) => setTimeout(r, (retryAfter + 1) * 1000));
        continue;
      }

      if (!rep.ok) return null;

      const setCookie = rep.headers.get("set-cookie") ?? "";
      const match = setCookie.match(/better-auth\.session_token=([^;]+)/);
      return match?.[1] ?? null;
    } catch {
      return null;
    }
  }
  return null;
}

async function creerCompte(): Promise<void> {
  await fetch(`${BASE_URL}/api/auth/sign-up/email`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Origin: BASE_URL },
    body: JSON.stringify({
      name: "E2E Test",
      email: E2E_EMAIL,
      password: E2E_PASSWORD,
    }),
    signal: AbortSignal.timeout(10000),
  }).catch(() => {});
}
