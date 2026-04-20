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
import { normaliserTexte, similarite } from "@strapontin/core";
import type { ConcertBrut } from "@strapontin/sources";
import { db } from "./db.js";

const logger = pino({ name: "persister" });

const STATUTS_INDISPONIBLES = new Set([
  "cancelled",
  "postponed",
  "rescheduled",
]);

const SEUIL_SIMILARITE_SALLE = 0.5;
const LONGUEUR_MIN_CONTAINMENT = 4;

export function estDoublonSalle(a: string, b: string): boolean {
  if (a === b) return true;
  if (similarite(a, b) >= SEUIL_SIMILARITE_SALLE) return true;
  const court = a.length <= b.length ? a : b;
  const long = a.length <= b.length ? b : a;
  return court.length >= LONGUEUR_MIN_CONTAINMENT && long.includes(court);
}

type ValeurConcert = {
  artiste: string;
  artisteNorm: string;
  date: string;
  heure: string | null;
  salle: string;
  salleNorm: string;
  ville: string | null;
  dept: string | null;
  prixMin: string | null;
  prixMax: string | null;
  url: string | null;
  source: string;
  classification: string | null;
  genre: string | null;
  disponible: boolean;
};

// Réécrit salleNorm des concerts entrants pour qu'ils correspondent à un existant en base
// quand la salle est sémantiquement la même mais nommée différemment selon la source.
// On réécrit uniquement salleNorm (clé de conflit), pas salle, afin que la règle
// de préférence casse-mixte de l'upsert puisse choisir le meilleur nom d'affichage.
async function reconcilierSalles(
  lot: ValeurConcert[],
): Promise<ValeurConcert[]> {
  const artistesNorms = [...new Set(lot.map((c) => c.artisteNorm))];

  const existants = await db
    .select({
      artisteNorm: concerts.artisteNorm,
      date: concerts.date,
      salleNorm: concerts.salleNorm,
    })
    .from(concerts)
    .where(inArray(concerts.artisteNorm, artistesNorms));

  const indexExistants = new Map<string, string[]>();
  for (const e of existants) {
    const cle = `${e.artisteNorm}|${e.date}`;
    const groupe = indexExistants.get(cle) ?? [];
    groupe.push(e.salleNorm);
    indexExistants.set(cle, groupe);
  }

  return lot.map((concert) => {
    const cle = `${concert.artisteNorm}|${concert.date}`;
    const sallesExistantes = indexExistants.get(cle);
    if (!sallesExistantes) return concert;

    for (const salleNormExistante of sallesExistantes) {
      if (salleNormExistante === concert.salleNorm) return concert;
      if (estDoublonSalle(concert.salleNorm, salleNormExistante)) {
        logger.debug(
          { entrant: concert.salleNorm, existant: salleNormExistante },
          "doublon salle inter-jobs réconcilié",
        );
        return { ...concert, salleNorm: salleNormExistante };
      }
    }
    return concert;
  });
}

// Dédoublonne par clé de conflit (artiste_norm, date, salle_norm) avant l'upsert.
// Le resolver canonicalise les noms après dedoublonner(), pouvant créer des doublons
// que PostgreSQL rejette avec "ON CONFLICT DO UPDATE command cannot affect row a second time".
// En cas de doublon, on conserve la version avec le plus de diacritiques.
// Score de qualité d'un concert pour le choix en cas de doublon.
// Cohérent avec les règles de l'upsert SQL (unaccent / upper).
function scoreQualite(c: ConcertBrut): number {
  const artisteDiacr = c.artiste.normalize("NFD").length - c.artiste.length;
  const salleDiacr = c.salle.normalize("NFD").length - c.salle.length;
  const artisteCasseMixte = c.artiste !== c.artiste.toUpperCase() ? 1 : 0;
  const salleCasseMixte = c.salle !== c.salle.toUpperCase() ? 1 : 0;
  return artisteDiacr * 10 + artisteCasseMixte * 5 + salleDiacr + salleCasseMixte;
}

export function deduplicerParCleConflit(liste: ConcertBrut[]): ConcertBrut[] {
  const parCle = new Map<string, ConcertBrut>();
  for (const c of liste) {
    const cle = `${normaliserTexte(c.artiste)}|${c.date}|${normaliserTexte(c.salle)}`;
    const existant = parCle.get(cle);
    if (!existant || scoreQualite(c) > scoreQualite(existant)) {
      parCle.set(cle, c);
    }
  }
  return [...parCle.values()];
}

// Upsert des concerts en base — retourne les IDs insérés/mis à jour
export async function persisterConcerts(
  liste: ConcertBrut[],
): Promise<string[]> {
  if (liste.length === 0) return [];

  const listeFiltree = deduplicerParCleConflit(liste);

  const ids: string[] = [];

  // Insérer par lots de 50
  const TAILLE_LOT = 50;
  for (let i = 0; i < listeFiltree.length; i += TAILLE_LOT) {
    const lot = listeFiltree.slice(i, i + TAILLE_LOT);

    const valeursBrutes: ValeurConcert[] = lot.map((c) => ({
      artiste: c.artiste,
      artisteNorm: normaliserTexte(c.artiste),
      date: c.date,
      heure: c.heure,
      salle: c.salle,
      salleNorm: normaliserTexte(c.salle),
      ville: c.ville,
      dept: c.dept,
      prixMin: c.prixMin !== null ? String(c.prixMin) : null,
      prixMax: c.prixMax !== null ? String(c.prixMax) : null,
      url: c.url,
      source: c.source,
      classification: c.classification,
      genre: c.genre,
      disponible:
        c.statut !== null ? !STATUTS_INDISPONIBLES.has(c.statut) : true,
    }));

    const valeurs = await reconcilierSalles(valeursBrutes);

    const inseres = await db
      .insert(concerts)
      .values(valeurs)
      .onConflictDoUpdate({
        target: [concerts.artisteNorm, concerts.date, concerts.salleNorm],
        set: {
          // Préférer le nom avec diacritiques (ex. "Julien Doré" > "Julien Dore")
          artiste: sql`CASE WHEN unaccent(excluded.artiste) != excluded.artiste THEN excluded.artiste ELSE ${concerts.artiste} END`,
          // Préférer la casse mixte sur le tout-majuscule (ex. "Accor Arena" > "ACCOR ARENA")
          salle: sql`CASE WHEN upper(excluded.salle) != excluded.salle THEN excluded.salle ELSE ${concerts.salle} END`,
          heure: sql`excluded.heure`,
          ville: sql`excluded.ville`,
          dept: sql`excluded.dept`,
          prixMin: sql`excluded.prix_min`,
          prixMax: sql`excluded.prix_max`,
          source: sql`excluded.source`,
          url: sql`COALESCE(excluded.url, ${concerts.url})`,
          classification: sql`COALESCE(excluded.classification, ${concerts.classification})`,
          genre: sql`COALESCE(excluded.genre, ${concerts.genre})`,
          disponible: sql`excluded.disponible`,
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
      const salleNorms = salleIds.map((id) => normaliserTexte(id));
      const parSalle = await db
        .select({ id: concerts.id })
        .from(concerts)
        .where(
          and(
            inArray(concerts.salleNorm, salleNorms),
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
