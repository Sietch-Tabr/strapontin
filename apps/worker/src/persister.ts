// Persiste les concerts dédoublonnés en base et met à jour user_matches.

import pino from "pino";
import { eq, and, inArray, sql } from "drizzle-orm";
import { concerts, userMatches, userArtistes, userSalles, userConfig } from "@strapontin/core/schema";
import { normaliserTexte } from "@strapontin/core";
import type { ConcertBrut } from "@strapontin/sources";
import { db } from "./db.js";

const logger = pino({ name: "persister" });

// Upsert des concerts en base — retourne les IDs insérés/mis à jour
export async function persisterConcerts(liste: ConcertBrut[]): Promise<string[]> {
  if (liste.length === 0) return [];

  const ids: string[] = [];

  // Insérer par lots de 50
  const TAILLE_LOT = 50;
  for (let i = 0; i < liste.length; i += TAILLE_LOT) {
    const lot = liste.slice(i, i + TAILLE_LOT);

    const valeurs = lot.map((c) => ({
      artiste: c.artiste,
      artisteNorm: normaliserTexte(c.artiste),
      date: c.date,
      heure: c.heure,
      salle: c.salle,
      ville: c.ville,
      dept: c.dept,
      prixMin: c.prixMin !== null ? String(c.prixMin) : null,
      prixMax: c.prixMax !== null ? String(c.prixMax) : null,
      url: c.url,
      source: c.source,
    }));

    const inseres = await db
      .insert(concerts)
      .values(valeurs)
      .onConflictDoUpdate({
        // Conflict sur (artiste_norm, date, salle) — à défaut d'une contrainte unique,
        // on met à jour les champs variables si la source est plus récente
        target: [concerts.artisteNorm, concerts.date, concerts.salle],
        set: {
          heure: sql`excluded.heure`,
          ville: sql`excluded.ville`,
          dept: sql`excluded.dept`,
          prixMin: sql`excluded.prix_min`,
          prixMax: sql`excluded.prix_max`,
          url: sql`COALESCE(excluded.url, ${concerts.url})`,
          updatedAt: new Date(),
        },
      })
      .returning({ id: concerts.id });

    ids.push(...inseres.map((r) => r.id));
  }

  logger.info({ total: ids.length }, "concerts persistés");
  return ids;
}

// Recalcule les user_matches pour les users concernés par les artistes/salles modifiés
export async function recalculerMatches(userIds?: string[]): Promise<void> {
  // Récupérer les users à recalculer
  const tousLesUsers = await db
    .selectDistinct({ userId: userArtistes.userId })
    .from(userArtistes);

  const sallesUsers = await db
    .selectDistinct({ userId: userSalles.userId })
    .from(userSalles);

  const usersSet = new Set([
    ...tousLesUsers.map((u) => u.userId),
    ...sallesUsers.map((u) => u.userId),
  ]);

  const usersARecalculer = userIds
    ? userIds.filter((id) => usersSet.has(id))
    : [...usersSet];

  const aujourdhui = new Date().toISOString().split("T")[0]!;

  for (const userId of usersARecalculer) {
    // Artistes suivis
    const artistes = await db
      .select({ artisteNormalise: userArtistes.artisteNormalise })
      .from(userArtistes)
      .where(eq(userArtistes.userId, userId));

    // Salles suivies
    const salles = await db
      .select({ salleId: userSalles.salleId })
      .from(userSalles)
      .where(eq(userSalles.userId, userId));

    if (artistes.length === 0 && salles.length === 0) continue;

    // Concerts correspondant aux artistes
    const artistesNorms = artistes.map((a) => a.artisteNormalise);
    const salleIds = salles.map((s) => s.salleId);

    const concertsCorrespondants: { id: string }[] = [];

    if (artistesNorms.length > 0) {
      const parArtiste = await db
        .select({ id: concerts.id })
        .from(concerts)
        .where(
          and(
            inArray(concerts.artisteNorm, artistesNorms),
            sql`${concerts.date} >= ${aujourdhui}`,
          ),
        );
      concertsCorrespondants.push(...parArtiste);
    }

    if (salleIds.length > 0) {
      const parSalle = await db
        .select({ id: concerts.id })
        .from(concerts)
        .where(
          and(
            inArray(concerts.salle, salleIds),
            sql`${concerts.date} >= ${aujourdhui}`,
          ),
        );
      concertsCorrespondants.push(...parSalle);
    }

    // Dédoublonner les IDs
    const concertIds = [...new Set(concertsCorrespondants.map((c) => c.id))];

    if (concertIds.length === 0) continue;

    // Upsert user_matches
    const valeurs = concertIds.map((concertId) => ({ userId, concertId }));

    await db
      .insert(userMatches)
      .values(valeurs)
      .onConflictDoNothing();

    logger.info({ userId, matches: concertIds.length }, "matches recalculés");
  }
}
