// Persiste les concerts dédoublonnés en base et met à jour user_matches.

import pino from "pino";
import { eq, and, inArray, notInArray, sql } from "drizzle-orm";
import {
  concerts,
  userMatches,
  userArtistes,
  userSalles,
  userConfig,
} from "@strapontin/core/schema";
import { normaliserTexte } from "@strapontin/core";
import type { ConcertBrut } from "@strapontin/sources";
import { db } from "./db.js";

const logger = pino({ name: "persister" });

// Upsert des concerts en base — retourne les IDs insérés/mis à jour
export async function persisterConcerts(
  liste: ConcertBrut[],
): Promise<string[]> {
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
      classification: c.classification,
      genre: c.genre,
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
          source: sql`excluded.source`,
          url: sql`COALESCE(excluded.url, ${concerts.url})`,
          classification: sql`COALESCE(excluded.classification, ${concerts.classification})`,
          genre: sql`COALESCE(excluded.genre, ${concerts.genre})`,
          updatedAt: sql`now()`,
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

    // Config user — départements pour filtrer les concerts d'artistes géographiquement
    const configs = await db
      .select({ departements: userConfig.departements })
      .from(userConfig)
      .where(eq(userConfig.userId, userId));
    const departements = configs[0]?.departements ?? [];

    // Concerts correspondant aux artistes
    const artistesNorms = artistes.map((a) => a.artisteNormalise);
    const salleIds = salles.map((s) => s.salleId);

    const concertsCorrespondants: { id: string }[] = [];

    if (artistesNorms.length > 0) {
      // Si l'user a configuré des départements, filtrer géographiquement (cohérent avec la CLI)
      const conditionsArtiste =
        departements.length > 0
          ? and(
              inArray(concerts.artisteNorm, artistesNorms),
              sql`${concerts.date} >= ${aujourdhui}`,
              inArray(concerts.dept, departements),
            )
          : and(
              inArray(concerts.artisteNorm, artistesNorms),
              sql`${concerts.date} >= ${aujourdhui}`,
            );

      const parArtiste = await db
        .select({ id: concerts.id })
        .from(concerts)
        .where(conditionsArtiste);
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

    if (concertIds.length === 0) {
      // Aucun concert correspondant → purger tous les matches de cet user
      await db.delete(userMatches).where(eq(userMatches.userId, userId));
      continue;
    }

    // Supprimer les matches périmés (artiste/salle supprimé, ou concert passé)
    // notInArray avec liste vide serait un SQL invalide — protégé par le guard ci-dessus
    await db
      .delete(userMatches)
      .where(
        and(
          eq(userMatches.userId, userId),
          notInArray(userMatches.concertId, concertIds),
        ),
      );

    // Insérer les nouveaux matches
    const valeurs = concertIds.map((concertId) => ({ userId, concertId }));
    await db.insert(userMatches).values(valeurs).onConflictDoNothing();

    logger.info({ userId, matches: concertIds.length }, "matches recalculés");
  }
}
