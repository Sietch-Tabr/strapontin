// Script de peuplement : importe une liste d'artistes pour un utilisateur.
// Usage : DATABASE_URL=... USER_EMAIL=... tsx scripts/seed-artistes.ts [fichier]
// Le fichier par défaut est scripts/artistes-seed.txt (un artiste par ligne).

import { drizzle } from "drizzle-orm/node-postgres";
import pkg from "pg";
const { Pool } = pkg;
import { userArtistes, users } from "../packages/core/src/schema.js";
import { eq, and } from "drizzle-orm";
import { readFileSync } from "fs";
import { resolve } from "path";

const DATABASE_URL = process.env.DATABASE_URL;
const USER_EMAIL = process.env.USER_EMAIL;

if (!DATABASE_URL) {
  console.error("DATABASE_URL manquant");
  process.exit(1);
}

if (!USER_EMAIL) {
  console.error("USER_EMAIL manquant — ex: USER_EMAIL=kwaite@example.com");
  process.exit(1);
}

const fichier = process.argv[2] ?? resolve(import.meta.dirname, "artistes-seed.txt");

function normaliserArtiste(nom: string): string {
  return nom
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

const pool = new Pool({ connectionString: DATABASE_URL });
const db = drizzle(pool);

async function main() {
  // Trouver l'utilisateur
  const usersFound = await db
    .select({ id: users.id, name: users.name })
    .from(users)
    .where(eq(users.email, USER_EMAIL!))
    .limit(1);

  if (usersFound.length === 0) {
    console.error(`Utilisateur introuvable : ${USER_EMAIL}`);
    process.exit(1);
  }

  const user = usersFound[0];
  console.log(`Utilisateur : ${user.name} (${user.id})`);

  // Lire la liste d'artistes
  const contenu = readFileSync(fichier, "utf-8");
  const artistes = contenu
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !l.startsWith("#"));

  console.log(`${artistes.length} artistes à importer depuis ${fichier}`);

  let ajoutes = 0;
  let ignores = 0;

  for (const artiste of artistes) {
    const normalise = normaliserArtiste(artiste);

    const existants = await db
      .select({ id: userArtistes.artisteNormalise })
      .from(userArtistes)
      .where(
        and(
          eq(userArtistes.userId, user.id),
          eq(userArtistes.artisteNormalise, normalise),
        ),
      );

    if (existants.length > 0) {
      console.log(`  [ignoré]  ${artiste}`);
      ignores++;
      continue;
    }

    await db.insert(userArtistes).values({
      userId: user.id,
      artisteNormalise: normalise,
      artisteAffiche: artiste,
    });
    console.log(`  [ajouté]  ${artiste}`);
    ajoutes++;
  }

  console.log(`\nTerminé : ${ajoutes} ajoutés, ${ignores} ignorés.`);
  await pool.end();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
