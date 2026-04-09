// Script de peuplement : importe les salles suivies pour un utilisateur.
// Usage : DATABASE_URL=... USER_EMAIL=... tsx scripts/seed-salles.ts

import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import { userSalles, users } from "../packages/core/src/schema.js";
import { eq, and } from "drizzle-orm";
import { fileURLToPath } from "url";

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

// Salles portées depuis la CLI Python (src/venues.py)
const SALLES_A_IMPORTER: { salleId: string; tmVenueId: string | null }[] = [
  { salleId: "Accor Arena", tmVenueId: "rZASnyZee9" },
  { salleId: "Dôme de Paris", tmVenueId: "rZASnyZde_" },
  { salleId: "Elysée Montmartre", tmVenueId: "rZ6SnyZdd7" },
  { salleId: "La Cigale", tmVenueId: "rZkSnyZaAe" },
  { salleId: "La Salle Pleyel", tmVenueId: "rZASnyZ1eE" },
  { salleId: "La Seine Musicale", tmVenueId: "rZ7SnyZa7mA" },
  { salleId: "Le Bataclan", tmVenueId: "rZ6SnyZe6d" },
  { salleId: "Le Grand Rex", tmVenueId: "rZkSnyZeae" },
  { salleId: "Le Trianon", tmVenueId: "rZASnyZ7eh" },
  { salleId: "L'Olympia", tmVenueId: "rZkSnyZ7Ae" },
  { salleId: "Musée du Louvre", tmVenueId: "rZFSnyZe6e1" },
  { salleId: "Paris La Défense Arena", tmVenueId: "rZ7SnyZaAGI" },
  { salleId: "Stade de France", tmVenueId: "Z598xZG2Ze717" },
  { salleId: "Théâtre Mogador", tmVenueId: "rZkSnyZe1e" },
  { salleId: "Zénith Paris - La Villette", tmVenueId: "rZkSnyZdke" },
  { salleId: "Opéra Bastille", tmVenueId: null },
  { salleId: "Palais Garnier", tmVenueId: null },
  { salleId: "Opéra-Comique", tmVenueId: null },
];

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

  const existantes = await db
    .select({ salleId: userSalles.salleId })
    .from(userSalles)
    .where(eq(userSalles.userId, user.id));

  const sallesExistantes = new Set(existantes.map((s) => s.salleId));

  const nouvelles = SALLES_A_IMPORTER.filter(
    (s) => !sallesExistantes.has(s.salleId),
  );
  const ignorees = SALLES_A_IMPORTER.length - nouvelles.length;

  if (nouvelles.length === 0) {
    console.log(
      `Aucune nouvelle salle à ajouter (${ignorees} déjà présentes).`,
    );
    await sql.end();
    return;
  }

  const valeurs = nouvelles.map((s) => ({
    userId: user.id,
    salleId: s.salleId,
    tmVenueId: s.tmVenueId,
  }));

  await db.insert(userSalles).values(valeurs).onConflictDoNothing();

  const avecId = nouvelles.filter((s) => s.tmVenueId !== null);
  const sansId = nouvelles.filter((s) => s.tmVenueId === null);

  for (const s of avecId) {
    console.log(`  [ajouté]  ${s.salleId} (TM: ${s.tmVenueId})`);
  }
  for (const s of sansId) {
    console.log(`  [ajouté]  ${s.salleId} (pas de tmVenueId)`);
  }
  if (ignorees > 0) {
    console.log(`  [ignorées] ${ignorees} salles déjà présentes`);
  }
  console.log(
    `\nTerminé : ${nouvelles.length} ajoutées (${avecId.length} avec tmVenueId), ${ignorees} ignorées.`,
  );
  console.log(
    `\nLancer fetch:salle manuellement ou attendre le cron 2h pour déclencher les fetches.`,
  );

  await sql.end();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
