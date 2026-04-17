// Script de rattrapage one-shot : corrige les noms de salles en TOUT MAJUSCULE
// en base en les remplaçant par le nom canonique (casse mixte) de l'API TM Venues.
//
// Usage : DATABASE_URL=... TICKETMASTER_API_KEY=... tsx scripts/corriger-noms-salles.ts

import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import { sql as drizzleSql, isNotNull, eq } from "drizzle-orm";
import { concerts, userSalles } from "../packages/core/src/schema.js";
import { normaliserTexte } from "../packages/core/src/normalisation.js";

const DATABASE_URL = process.env.DATABASE_URL;
const API_KEY = process.env.TICKETMASTER_API_KEY;

if (!DATABASE_URL) {
  console.error("DATABASE_URL manquant");
  process.exit(1);
}
if (!API_KEY) {
  console.error("TICKETMASTER_API_KEY manquant");
  process.exit(1);
}

const BASE_URL = "https://app.ticketmaster.com/discovery/v2";

async function obtenirNomSalle(venueId: string): Promise<string | null> {
  const url =
    `${BASE_URL}/venues/${encodeURIComponent(venueId)}.json` +
    `?apikey=${API_KEY}&locale=fr`;
  try {
    const res = await fetch(url);
    if (!res.ok) {
      console.warn(`  [warn] GET /venues/${venueId} → HTTP ${res.status}`);
      return null;
    }
    const data = (await res.json()) as Record<string, unknown>;
    return (data["name"] as string | undefined) ?? null;
  } catch (err) {
    console.warn(`  [warn] GET /venues/${venueId} → ${err}`);
    return null;
  }
}

const pg = postgres(DATABASE_URL!);
const db = drizzle(pg);

async function main() {
  const sallesAvecId = await db
    .selectDistinct({
      salleId: userSalles.salleId,
      tmVenueId: userSalles.tmVenueId,
    })
    .from(userSalles)
    .where(isNotNull(userSalles.tmVenueId));

  console.log(`${sallesAvecId.length} salle(s) avec tmVenueId trouvées.`);

  let corrigees = 0;
  let ignorees = 0;
  let erreurs = 0;

  for (const { salleId, tmVenueId } of sallesAvecId) {
    const nomCanonique = await obtenirNomSalle(tmVenueId!);
    if (!nomCanonique) {
      erreurs++;
      continue;
    }

    // Ne mettre à jour que les entrées dont le nom est tout-majuscule
    const salleNorm = normaliserTexte(salleId);
    const resultat = await db
      .update(concerts)
      .set({ salle: nomCanonique })
      .where(
        drizzleSql`${concerts.salleNorm} = ${salleNorm}
          AND upper(${concerts.salle}) = ${concerts.salle}`,
      )
      .returning({ id: concerts.id });

    if (resultat.length > 0) {
      console.log(
        `  [corrigé]  "${salleId}" → "${nomCanonique}" (${resultat.length} concert(s))`,
      );
      corrigees += resultat.length;
    } else {
      console.log(`  [ignoré]   "${salleId}" — aucun concert tout-majuscule`);
      ignorees++;
    }

    // Respecter le rate limit TM (5 req/s)
    await new Promise((r) => setTimeout(r, 250));
  }

  console.log(
    `\nTerminé : ${corrigees} concert(s) corrigés, ${ignorees} salle(s) ignorées, ${erreurs} erreur(s).`,
  );

  await pg.end();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
