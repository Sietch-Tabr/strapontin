import { request } from "@playwright/test";
import { execSync } from "child_process";
import { existsSync, readFileSync } from "fs";

const BASE_URL = process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";

export const FICHIER_AUTH_ADMIN = "tests/e2e/.auth-admin.json";
export const FICHIER_AUTH_USER = "tests/e2e/.auth-user.json";

const E2E_ADMIN_IDENTIFIANT = "e2eadmin";
const E2E_ADMIN_MOT_DE_PASSE =
  process.env["E2E_ADMIN_MOT_DE_PASSE"] ?? "e2e-admin-2026";
const E2E_USER_IDENTIFIANT = "e2euser";
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
  const contexte = await request.newContext({
    baseURL,
    extraHTTPHeaders: { "X-Requested-With": "strapontin" },
  });
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

async function reinitialiserRateLimits(): Promise<void> {
  const contexte = await request.newContext({
    baseURL: BASE_URL,
    storageState: FICHIER_AUTH_ADMIN,
    extraHTTPHeaders: { "X-Requested-With": "strapontin" },
  });
  try {
    const rep = await contexte.delete("/api/salles/rate-limits/signalement");
    if (rep.status() === 204) {
      console.log("[e2e setup] buckets rate-limit signalement réinitialisés");
    } else if (rep.status() === 404) {
      console.log(
        "[e2e setup] endpoint reset rate-limit absent (profil prod ?)",
      );
    }
    const repEnvois = await contexte.delete("/api/admin/invitations/envois");
    if (repEnvois.status() === 204) {
      console.log("[e2e setup] cadence d'envoi des invitations réinitialisée");
    }
  } finally {
    await contexte.dispose();
  }
}

function listerPidsPlaywrightMcp(): string[] {
  let sortie: string;
  try {
    sortie = execSync("ps aux", { encoding: "utf-8" });
  } catch {
    return []; // ps indisponible sur cette plateforme — ne bloque pas la suite pour autant
  }
  // Seul l'exécutable compte : un shell dont la commande cite « playwright-mcp » (grep, pkill)
  // n'est pas une session d'automatisation.
  return sortie
    .split("\n")
    .map((ligne) => ligne.trim().split(/\s+/))
    .filter((champs) => {
      const [executable, script = ""] = champs.slice(10);
      return (
        /playwright-mcp$/i.test(executable ?? "") ||
        (/(^|\/)node$/.test(executable ?? "") && /playwright-mcp/i.test(script))
      );
    })
    .map((champs) => champs[1])
    .filter((pid): pid is string => !!pid);
}

// Une session playwright-mcp (debug manuel) active en parallèle d'une suite E2E automatisée
// ralentit fortement l'ensemble (facteur ~4x observé en session, faux timeouts en cascade sur
// des tests sans rapport) sans jamais faire échouer clairement le run — juste le rendre très
// lent, ce qui masque la vraie cause pendant potentiellement des dizaines de minutes. Échouer
// immédiatement avec un message explicite plutôt que de laisser la suite tourner en silence.
//
// Un process playwright-mcp peut aussi apparaître de façon transitoire (reconnexion MCP,
// quelques centaines de ms) sans jamais interférer réellement — un seul instantané `ps aux`
// bloquerait alors des runs parfaitement sains. On ne retient donc que les PID encore présents
// après un court délai, seule signature d'une session réellement soutenue.
async function verifierAucunProcessAutomationParallele(): Promise<void> {
  const premierReleve = listerPidsPlaywrightMcp();
  if (premierReleve.length === 0) return;

  await new Promise((resolve) => setTimeout(resolve, 3000));
  const secondReleve = new Set(listerPidsPlaywrightMcp());
  const pidsPersistants = premierReleve.filter((pid) => secondReleve.has(pid));

  if (pidsPersistants.length > 0) {
    const pids = pidsPersistants.join(", ");
    throw new Error(
      `Processus playwright-mcp détecté(s) en parallèle (PID ${pids}, toujours présents après ` +
        "3s) — une automatisation navigateur externe active pendant la suite E2E la ralentit " +
        "fortement (facteur ~4x observé) et fausse les résultats. Arrêter ce(s) process avant " +
        `de relancer : kill ${pids}`,
    );
  }
}

export default async function setup(): Promise<() => Promise<void>> {
  await verifierAucunProcessAutomationParallele();

  const adminValide = sessionValide(FICHIER_AUTH_ADMIN);
  const userValide = sessionValide(FICHIER_AUTH_USER);

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

  if (adminValide && userValide) {
    console.log("[e2e setup] sessions admin + user existantes réutilisées");
  }

  await reinitialiserRateLimits();

  return async () => {};
}
