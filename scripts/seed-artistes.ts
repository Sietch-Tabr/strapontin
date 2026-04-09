// Script de peuplement : importe une liste d'artistes pour un utilisateur.
// Usage : DATABASE_URL=... USER_EMAIL=... tsx scripts/seed-artistes.ts [fichier]
// Le fichier par défaut est scripts/artistes-seed.txt (un artiste par ligne).

import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import { userArtistes, users } from "../packages/core/src/schema.js";
import { eq } from "drizzle-orm";
import { readFileSync } from "fs";
import { resolve } from "path";

const DATABASE_URL = process.env.DATABASE_URL;
const USER_EMAIL = process.env.USER_EMAIL;

if (!DATABASE_URL) {
  console.error("DATABASE_URL manquant");
  process.exit(1);
}

if (!USER_EMAIL) {
  console.error("USER_EMAIL manquant — ex: USER_EMAIL=kwaite@hotmail.fr");
  process.exit(1);
}

const fichier =
  process.argv[2] ?? resolve(import.meta.dirname, "artistes-seed.txt");

function normaliserArtiste(nom: string): string {
  return nom
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

const sql = postgres(DATABASE_URL!);
const db = drizzle(sql);

async function main() {
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

  const contenu = readFileSync(fichier, "utf-8");
  const artistes = contenu
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !l.startsWith("#"));

  console.log(`${artistes.length} artistes à importer depuis ${fichier}`);

  const existants = await db
    .select({ artisteNormalise: userArtistes.artisteNormalise })
    .from(userArtistes)
    .where(eq(userArtistes.userId, user.id));

  const normalisesExistants = new Set(existants.map((e) => e.artisteNormalise));

  const nouveaux = artistes.filter(
    (a) => !normalisesExistants.has(normaliserArtiste(a)),
  );
  const ignores = artistes.length - nouveaux.length;

  if (nouveaux.length === 0) {
    console.log(`Aucun nouvel artiste à ajouter (${ignores} déjà présents).`);
    await sql.end();
    return;
  }

  const valeurs = nouveaux.map((artiste) => ({
    userId: user.id,
    artisteNormalise: normaliserArtiste(artiste),
    artisteAffiche: artiste,
  }));

  await db.insert(userArtistes).values(valeurs).onConflictDoNothing();

  for (const a of nouveaux) {
    console.log(`  [ajouté]  ${a}`);
  }
  if (ignores > 0) {
    console.log(`  [ignorés] ${ignores} artistes déjà présents`);
  }
  console.log(`\nTerminé : ${nouveaux.length} ajoutés, ${ignores} ignorés.`);

  await sql.end();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
