// Persiste les concerts dédoublonnés en base et met à jour user_matches.

import pino from "pino";
import {
  eq,
  and,
  inArray,
  notInArray,
  isNull,
  not,
  or,
  sql,
} from "drizzle-orm";
import {
  concerts,
  userMatches,
  userArtistes,
  userSalles,
  userConfig,
  users,
} from "@strapontin/core/schema";
import { normaliserTexte, similarite } from "@strapontin/core";
import type { ConcertBrut } from "@strapontin/sources";
import { db } from "./db.js";
import { envoyerDigestConcerts } from "./email.js";
import { envoyerPushDigest } from "./push.js";

const logger = pino({ name: "persister" });

const STATUTS_INDISPONIBLES = new Set([
  "cancelled",
  "offsale",
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

export type ValeurConcert = {
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
  statut: string | null;
};

// Réécrit salleNorm des concerts entrants pour qu'ils correspondent à un existant en base
// quand la salle est sémantiquement la même mais nommée différemment selon la source.
// On réécrit uniquement salleNorm (clé de conflit), pas salle, afin que la règle
// de préférence casse-mixte de l'upsert puisse choisir le meilleur nom d'affichage.
// Deux passes : intra-lot d'abord, puis contre la base.
async function reconcilierSalles(
  lot: ValeurConcert[],
): Promise<ValeurConcert[]> {
  // Passe 1 — réconciliation intra-lot
  // TM peut retourner le même event avec deux noms de salle légèrement différents
  // (ex. "salle pleyel" / "salle pleyel paris 08") dans le même lot.
  // On unifie vers le salleNorm le plus long (le plus spécifique).
  const remplacementsIntraLot = new Map<string, string>();
  const parGroupe = new Map<string, string[]>();

  for (const concert of lot) {
    const cle = `${concert.artisteNorm}|${concert.date}`;
    const groupe = parGroupe.get(cle) ?? [];
    if (!groupe.includes(concert.salleNorm)) groupe.push(concert.salleNorm);
    parGroupe.set(cle, groupe);
  }

  for (const sallesNorm of parGroupe.values()) {
    if (sallesNorm.length <= 1) continue;
    for (let i = 0; i < sallesNorm.length; i++) {
      for (let j = i + 1; j < sallesNorm.length; j++) {
        const a = sallesNorm[i]!;
        const b = sallesNorm[j]!;
        if (estDoublonSalle(a, b)) {
          const canonique = a.length >= b.length ? a : b;
          remplacementsIntraLot.set(a, canonique);
          remplacementsIntraLot.set(b, canonique);
        }
      }
    }
  }

  const lotApresIntraLot =
    remplacementsIntraLot.size === 0
      ? lot
      : lot.map((concert) => {
          const canonique = remplacementsIntraLot.get(concert.salleNorm);
          if (!canonique || canonique === concert.salleNorm) return concert;
          logger.debug(
            { entrant: concert.salleNorm, canonique },
            "doublon salle intra-lot réconcilié",
          );
          return { ...concert, salleNorm: canonique };
        });

  // Passe 2 — réconciliation contre la base
  const artistesNorms = [
    ...new Set(lotApresIntraLot.map((c) => c.artisteNorm)),
  ];

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

  return lotApresIntraLot.map((concert) => {
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

// Score de qualité commun — diacritiques et casse mixte préférés, cohérent avec l'upsert SQL.
function scoreArtisteSalle(artiste: string, salle: string): number {
  const artisteDiacr = artiste.normalize("NFD").length - artiste.length;
  const salleDiacr = salle.normalize("NFD").length - salle.length;
  const artisteCasseMixte = artiste !== artiste.toUpperCase() ? 1 : 0;
  const salleCasseMixte = salle !== salle.toUpperCase() ? 1 : 0;
  return (
    artisteDiacr * 10 + artisteCasseMixte * 5 + salleDiacr + salleCasseMixte
  );
}

// Dédoublonne par clé de conflit (artiste_norm, date, salle_norm) avant l'upsert.
// Le resolver canonicalise les noms après dedoublonner(), pouvant créer des doublons
// que PostgreSQL rejette avec "ON CONFLICT DO UPDATE command cannot affect row a second time".
export function deduplicerParCleConflit(liste: ConcertBrut[]): ConcertBrut[] {
  const parCle = new Map<string, ConcertBrut>();
  for (const c of liste) {
    const cle = `${normaliserTexte(c.artiste)}|${c.date}|${normaliserTexte(c.salle)}`;
    const existant = parCle.get(cle);
    if (
      !existant ||
      scoreArtisteSalle(c.artiste, c.salle) >
        scoreArtisteSalle(existant.artiste, existant.salle)
    ) {
      parCle.set(cle, c);
    }
  }
  return [...parCle.values()];
}

// Deuxième passe après reconcilierSalles : les réécritures de salleNorm peuvent créer
// de nouveaux doublons intra-lot (ex. deux salles réconciliées vers la même clé).
export function deduplicerLotReconcilie(lot: ValeurConcert[]): ValeurConcert[] {
  const parCle = new Map<string, ValeurConcert>();
  for (const v of lot) {
    const cle = `${v.artisteNorm}|${v.date}|${v.salleNorm}`;
    const existant = parCle.get(cle);
    if (
      !existant ||
      scoreArtisteSalle(v.artiste, v.salle) >
        scoreArtisteSalle(existant.artiste, existant.salle)
    ) {
      parCle.set(cle, v);
    }
  }
  return [...parCle.values()];
}

// Upsert des concerts en base — retourne les IDs insérés/mis à jour
export async function persisterConcerts(
  liste: ConcertBrut[],
): Promise<string[]> {
  if (liste.length === 0) return [];

  const apresDedup = deduplicerParCleConflit(liste);
  const listeFiltree = apresDedup.filter((c) => {
    if (normaliserTexte(c.artiste).length === 0) {
      logger.warn(
        { artiste: c.artiste, date: c.date, salle: c.salle },
        "concert rejeté — artiste_norm vide",
      );
      return false;
    }
    return true;
  });

  const ids: string[] = [];
  let totalInserts = 0;
  let totalUpdates = 0;

  // Insérer par lots de 500 — PG gère sans problème, réduit les aller-retours DB
  const TAILLE_LOT = 500;
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
      statut: c.statut ?? null,
    }));

    const valeurs = deduplicerLotReconcilie(
      await reconcilierSalles(valeursBrutes),
    );

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
          statut: sql`excluded.statut`,
          updatedAt: sql`now()`,
        },
      })
      .returning({ id: concerts.id, estInsertion: sql<boolean>`(xmax = 0)` });

    for (const r of inseres) {
      ids.push(r.id);
      if (r.estInsertion) totalInserts++;
      else totalUpdates++;
    }
  }

  logger.info({ total: ids.length, inserts: totalInserts, updates: totalUpdates }, "concerts persistés");
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

    // Les concerts indisponibles restent visibles dans /spectacles mais ne doivent pas
    // afficher le badge Nouveau — on les marque notifiés immédiatement sans envoyer de notif.
    await db
      .update(userMatches)
      .set({ notifieLe: new Date() })
      .where(
        and(
          eq(userMatches.userId, userId),
          isNull(userMatches.notifieLe),
          inArray(
            userMatches.concertId,
            db
              .select({ id: concerts.id })
              .from(concerts)
              .where(
                and(
                  inArray(concerts.id, concertIds),
                  eq(concerts.disponible, false),
                ),
              ),
          ),
        ),
      );

    logger.info({ userId, matches: concertIds.length }, "matches recalculés");
  }

  // Notifier les users en mode temps_reel après recalcul
  await notifierUsersTempsReel(usersARecalculer);
}

// Envoie un digest aux users configurés en 'temps_reel' pour les matches non encore notifiés.
// Appelée après recalculerMatches — ne couvre pas les users en mode 'quotidien'.
export async function notifierUsersTempsReel(userIds: string[]): Promise<void> {
  if (userIds.length === 0) return;

  for (const userId of userIds) {
    const config = await db
      .select({
        frequenceNotifications: userConfig.frequenceNotifications,
      })
      .from(userConfig)
      .where(eq(userConfig.userId, userId))
      .then((rows) => rows[0] ?? null);

    if (config?.frequenceNotifications !== "temps_reel") continue;

    const userRow = await db
      .select({ email: users.email })
      .from(users)
      .where(eq(users.id, userId))
      .then((rows) => rows[0] ?? null);

    if (!userRow) continue;

    await envoyerDigestPourUser(userId, userRow.email);
  }
}

// Envoie un digest aux users configurés en 'quotidien' pour les matches non encore notifiés.
// Appelée par le job notifier:digest planifié chaque matin.
export async function notifierUsersQuotidien(): Promise<void> {
  const usersQuotidien = await db
    .select({ userId: userConfig.userId })
    .from(userConfig)
    .where(eq(userConfig.frequenceNotifications, "quotidien"));

  for (const { userId } of usersQuotidien) {
    const userRow = await db
      .select({ email: users.email })
      .from(users)
      .where(eq(users.id, userId))
      .then((rows) => rows[0] ?? null);

    if (!userRow) continue;

    await envoyerDigestPourUser(userId, userRow.email);
  }
}

async function envoyerDigestPourUser(
  userId: string,
  email: string,
): Promise<void> {
  const prefs = await db
    .select({
      notifArtistes: userConfig.notifArtistes,
      notifSalles: userConfig.notifSalles,
    })
    .from(userConfig)
    .where(eq(userConfig.userId, userId))
    .then((rows) => rows[0] ?? { notifArtistes: true, notifSalles: false });

  if (!prefs.notifArtistes && !prefs.notifSalles) return;

  const conditionsFiltrage: ReturnType<typeof eq>[] = [];

  if (prefs.notifArtistes) {
    const artisteNorms = await db
      .select({ artisteNormalise: userArtistes.artisteNormalise })
      .from(userArtistes)
      .where(eq(userArtistes.userId, userId));
    if (artisteNorms.length > 0) {
      conditionsFiltrage.push(
        inArray(
          concerts.artisteNorm,
          artisteNorms.map((a) => a.artisteNormalise),
        ),
      );
    }
  }

  if (prefs.notifSalles) {
    const salles = await db
      .select({ salleId: userSalles.salleId })
      .from(userSalles)
      .where(eq(userSalles.userId, userId));
    if (salles.length > 0) {
      conditionsFiltrage.push(
        inArray(
          concerts.salleNorm,
          salles.map((s) => normaliserTexte(s.salleId)),
        ),
      );
    }
  }

  if (conditionsFiltrage.length === 0) return;

  const filtreConcerts =
    conditionsFiltrage.length === 1
      ? conditionsFiltrage[0]!
      : or(...conditionsFiltrage)!;

  const nouveauxMatches = await db
    .select({
      concertId: userMatches.concertId,
      artiste: concerts.artiste,
      date: concerts.date,
      salle: concerts.salle,
      ville: concerts.ville,
      url: concerts.url,
    })
    .from(userMatches)
    .innerJoin(concerts, eq(userMatches.concertId, concerts.id))
    .where(
      and(
        eq(userMatches.userId, userId),
        isNull(userMatches.notifieLe),
        isNull(userMatches.masqueLe),
        or(
          isNull(concerts.statut),
          not(
            inArray(concerts.statut, ["cancelled", "offsale", "postponed", "rescheduled"]),
          ),
        ),
        filtreConcerts,
      ),
    );

  if (nouveauxMatches.length === 0) return;

  const concertsPourNotif = nouveauxMatches.map((m) => ({
    artiste: m.artiste,
    date: m.date,
    salle: m.salle,
    ville: m.ville,
    url: m.url,
  }));

  const resultats = await Promise.allSettled([
    envoyerDigestConcerts({ email, concerts: concertsPourNotif }),
    envoyerPushDigest(userId, concertsPourNotif),
  ]);

  const emailOk =
    resultats[0].status === "fulfilled" && resultats[0].value === true;
  const pushOk =
    resultats[1].status === "fulfilled" && resultats[1].value === true;

  if (emailOk || pushOk) {
    const concertIds = nouveauxMatches.map((m) => m.concertId);
    await db
      .update(userMatches)
      .set({ notifieLe: new Date() })
      .where(
        and(
          eq(userMatches.userId, userId),
          inArray(userMatches.concertId, concertIds),
        ),
      );
    logger.info({ userId, emailOk, pushOk, nb: concertIds.length }, "notifié");
  } else {
    logger.warn({ userId }, "notification échouée — notifie_le non mis à jour");
  }
}
