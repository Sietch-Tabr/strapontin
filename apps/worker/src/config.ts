import {
  createCipheriv,
  createDecipheriv,
  randomBytes,
  scryptSync,
} from "crypto";
import { db } from "./db.js";
import { appConfig } from "@strapontin/core/schema";
import { eq } from "drizzle-orm";
import { logAdmin } from "./log-admin.js";

const secret = process.env["BETTER_AUTH_SECRET"];
if (!secret) throw new Error("BETTER_AUTH_SECRET est requis au démarrage");
const cleChiffrement = scryptSync(secret, "strapontin-app-config", 32);

const CACHE_TTL_MS = 5 * 60 * 1000;
const cacheConfig = new Map<string, { valeur: string | null; expireAt: number }>();

function chiffrer(valeur: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", cleChiffrement, iv);
  const chiffre = Buffer.concat([
    cipher.update(valeur, "utf8"),
    cipher.final(),
  ]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, tag, chiffre]).toString("base64");
}

function dechiffrer(chiffre: string): string {
  const buf = Buffer.from(chiffre, "base64");
  const iv = buf.subarray(0, 12);
  const tag = buf.subarray(12, 28);
  const donnees = buf.subarray(28);
  const decipher = createDecipheriv("aes-256-gcm", cleChiffrement, iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(donnees), decipher.final()]).toString(
    "utf8",
  );
}

export async function lireConfig(cle: string): Promise<string | null> {
  const entree = cacheConfig.get(cle);
  if (entree && Date.now() < entree.expireAt) return entree.valeur;

  const lignes = await db
    .select({ valeurChiffree: appConfig.valeurChiffree })
    .from(appConfig)
    .where(eq(appConfig.cle, cle))
    .limit(1);

  let valeur: string | null = null;
  if (lignes[0]) {
    try {
      valeur = dechiffrer(lignes[0].valeurChiffree);
    } catch {
      valeur = null;
      void logAdmin("warn", "config", "déchiffrement échoué", { cle });
    }
  }

  cacheConfig.set(cle, { valeur, expireAt: Date.now() + CACHE_TTL_MS });
  return valeur;
}

export async function sauvegarderConfig(
  cle: string,
  valeur: string,
): Promise<void> {
  cacheConfig.delete(cle);
  if (!valeur) {
    await db.delete(appConfig).where(eq(appConfig.cle, cle));
    void logAdmin("info", "config", "config supprimée", { cle });
    return;
  }
  const valeurChiffree = chiffrer(valeur);
  await db
    .insert(appConfig)
    .values({ cle, valeurChiffree })
    .onConflictDoUpdate({
      target: appConfig.cle,
      set: { valeurChiffree, misAJourLe: new Date() },
    });
  void logAdmin("info", "config", "config sauvegardée", { cle });
}

export { chiffrer, dechiffrer };
