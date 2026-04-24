import { createCipheriv, createDecipheriv, randomBytes, scryptSync } from "crypto";
import { db } from "./db";
import { appConfig } from "@strapontin/core/schema";
import { eq } from "drizzle-orm";

const cleChiffrement = scryptSync(
  process.env["BETTER_AUTH_SECRET"] ?? "dev-secret",
  "strapontin-app-config",
  32,
);

function chiffrer(valeur: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", cleChiffrement, iv);
  const chiffre = Buffer.concat([cipher.update(valeur, "utf8"), cipher.final()]);
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
  return Buffer.concat([decipher.update(donnees), decipher.final()]).toString("utf8");
}

export async function lireConfig(cle: string): Promise<string | null> {
  const lignes = await db
    .select({ valeurChiffree: appConfig.valeurChiffree })
    .from(appConfig)
    .where(eq(appConfig.cle, cle))
    .limit(1);
  if (!lignes[0]) return null;
  try {
    return dechiffrer(lignes[0].valeurChiffree);
  } catch {
    return null;
  }
}

export async function sauvegarderConfig(cle: string, valeur: string): Promise<void> {
  if (!valeur) {
    await db.delete(appConfig).where(eq(appConfig.cle, cle));
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
}

export async function lireConfigsPresentes(): Promise<Set<string>> {
  const lignes = await db.select({ cle: appConfig.cle }).from(appConfig);
  return new Set(lignes.map((l) => l.cle));
}
