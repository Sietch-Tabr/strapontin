import { db } from "$lib/server/db";
import { queue } from "$lib/server/queue";
import { userSalles, concerts } from "@strapontin/core/schema";
import { levenshtein } from "@strapontin/core";
import { eq, and, ilike, sql, count } from "drizzle-orm";
import { fail } from "@sveltejs/kit";
import { lireConfig } from "$lib/server/config";
import type { PageServerLoad, Actions } from "./$types";

const TM_VENUES_URL = "https://app.ticketmaster.com/discovery/v2/venues.json";

// Normalise un nom de salle pour la déduplication : retire accents, ponctuation et
// caractères corrompus (??) qui peuvent apparaître selon la source de données.
function normaliserSalleCle(nom: string): string {
  return nom
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]/g, "");
}

// Retourne true si deux noms de salle désignent le même lieu, en tolérant jusqu'à
// 2 caractères d'écart après normalisation (gère les corruptions d'encodage type "D??fense").
function estMemeSalle(a: string, b: string): boolean {
  const na = normaliserSalleCle(a);
  const nb = normaliserSalleCle(b);
  if (na === nb) return true;
  // Seuil proportionnel à la longueur pour éviter les faux positifs sur les noms courts
  const seuil = Math.max(2, Math.floor(Math.min(na.length, nb.length) * 0.1));
  return levenshtein(na, nb) <= seuil;
}

interface ResultatSalle {
  salle: string;
  ville: string | null;
  tmVenueId: string | null;
  source: "base" | "ticketmaster";
}

async function rechercherSallesTM(terme: string): Promise<ResultatSalle[]> {
  const TICKETMASTER_API_KEY = await lireConfig("TICKETMASTER_API_KEY");
  if (!TICKETMASTER_API_KEY) return [];
  try {
    const url =
      `${TM_VENUES_URL}?keyword=${encodeURIComponent(terme)}` +
      `&countryCode=FR&size=6&apikey=${TICKETMASTER_API_KEY}`;
    const resp = await fetch(url, { signal: AbortSignal.timeout(3000) });
    if (!resp.ok) return [];
    const data = (await resp.json()) as {
      _embedded?: {
        venues?: { id: string; name: string; city?: { name: string } }[];
      };
    };
    return (data._embedded?.venues ?? []).map((v) => ({
      salle: v.name,
      ville: v.city?.name ?? null,
      tmVenueId: v.id ?? null,
      source: "ticketmaster" as const,
    }));
  } catch {
    return [];
  }
}

export const load: PageServerLoad = async ({ locals, url }) => {
  const recherche = (url.searchParams.get("q")?.trim() ?? "").slice(0, 100);

  const sallesSuivies = await db
    .select({ salleId: userSalles.salleId })
    .from(userSalles)
    .where(eq(userSalles.userId, locals.user!.id))
    .orderBy(sql`lower(${userSalles.salleId})`);

  const sallesDejaAjoutees = new Set(sallesSuivies.map((s) => s.salleId));
  let resultatsRecherche: ResultatSalle[] = [];

  if (recherche.length >= 2) {
    // Chercher d'abord dans les concerts en base
    const enBase = await db
      .selectDistinct({ salle: concerts.salle, ville: concerts.ville })
      .from(concerts)
      .where(ilike(concerts.salle, `%${recherche}%`))
      .orderBy(concerts.salle)
      .limit(10);

    resultatsRecherche = enBase
      .filter((r) => !sallesDejaAjoutees.has(r.salle))
      .map((r) => ({ ...r, tmVenueId: null, source: "base" as const }));

    // Compléter avec Ticketmaster si < 3 résultats
    if (resultatsRecherche.length < 3) {
      const tmResultats = await rechercherSallesTM(recherche);
      // Déduplication fuzzy (distance ≤ 2) pour gérer les corruptions d'encodage
      // ex : "Paris La D??fense Arena" (DB) ≈ "Paris La Defense Arena" (TM)
      const sallesDejaPresentes = [
        ...resultatsRecherche.map((r) => r.salle),
        ...[...sallesDejaAjoutees],
      ];
      for (const r of tmResultats) {
        if (!sallesDejaPresentes.some((s) => estMemeSalle(s, r.salle))) {
          resultatsRecherche.push(r);
          sallesDejaPresentes.push(r.salle);
        }
      }
    }
  }

  return {
    sallesSuivies,
    resultatsRecherche,
    recherche,
  };
};

export const actions: Actions = {
  ajouter: async ({ request, locals }) => {
    const donnees = await request.formData();
    const salleId = donnees.get("salleId")?.toString().trim() ?? "";
    const tmVenueId = donnees.get("tmVenueId")?.toString().trim() || null;

    if (!salleId || salleId.length > 200) {
      return fail(400, { erreur: "Identifiant de salle invalide." });
    }

    const existants = await db
      .select({ salleId: userSalles.salleId })
      .from(userSalles)
      .where(
        and(
          eq(userSalles.userId, locals.user!.id),
          eq(userSalles.salleId, salleId),
        ),
      );

    if (existants.length > 0) {
      return fail(409, { erreur: "Cette salle est déjà dans votre liste." });
    }

    const [{ total }] = await db
      .select({ total: count() })
      .from(userSalles)
      .where(eq(userSalles.userId, locals.user!.id));

    if (total >= 200) {
      return fail(400, { erreur: "Limite de 200 salles atteinte." });
    }

    await db.insert(userSalles).values({
      userId: locals.user!.id,
      salleId,
      tmVenueId,
    });

    if (tmVenueId) {
      // Déclencher fetch:salle immédiatement si on a l'ID Ticketmaster
      await queue
        .add(
          "fetch:salle",
          { type: "fetch:salle", venueId: tmVenueId, userId: locals.user!.id },
          { removeOnComplete: { count: 10 }, removeOnFail: { count: 20 } },
        )
        .catch(() => {
          /* le worker est peut-être arrêté en dev */
        });
    } else {
      await queue
        .add(
          "recalculer:matches",
          { type: "recalculer:matches", userIds: [locals.user!.id] },
          { removeOnComplete: { count: 10 }, removeOnFail: { count: 20 } },
        )
        .catch(() => {
          /* le worker est peut-être arrêté en dev */
        });
    }

    return { succes: true };
  },

  supprimer: async ({ request, locals }) => {
    const donnees = await request.formData();
    const salleId = donnees.get("salleId")?.toString() ?? "";

    if (!salleId) {
      return fail(400, { erreur: "Identifiant manquant." });
    }

    await db
      .delete(userSalles)
      .where(
        and(
          eq(userSalles.userId, locals.user!.id),
          eq(userSalles.salleId, salleId),
        ),
      );

    return { succes: true };
  },
};
