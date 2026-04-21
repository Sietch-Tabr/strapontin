import pino from "pino";
import type { ConcertBrut, SourceConcerts } from "./types.js";

export interface VenueTM {
  id: string;
  nom: string;
  ville: string | null;
}

const logger = pino({ name: "source:ticketmaster" });

const BASE_URL = "https://app.ticketmaster.com/discovery/v2";
const CLASSIFICATIONS = ["music", "theatre", "opera", "musical"];
const TAILLE_PAGE = 20;
// Taille maximale autorisée par TM pour les appels groupés (région, batch artistes)
const TAILLE_PAGE_REGION = 200;
// Protection quota : ne jamais dépasser 5 pages par appel (max 1 000 concerts)
const NB_PAGES_MAX = 5;

export interface QuotaTM {
  dispo: number;
  total: number;
  reset: number; // Unix timestamp ms
  misAJour: string; // ISO 8601
}

// Correspondance code département → latitude/longitude centre
const CENTRES_DEPT: Record<string, { lat: number; lng: number }> = {
  "01": { lat: 46.09, lng: 5.32 },
  "02": { lat: 49.56, lng: 3.53 },
  "03": { lat: 46.35, lng: 3.18 },
  "04": { lat: 44.08, lng: 6.15 },
  "05": { lat: 44.58, lng: 6.13 },
  "06": { lat: 43.85, lng: 7.11 },
  "07": { lat: 44.78, lng: 4.46 },
  "08": { lat: 49.61, lng: 4.65 },
  "09": { lat: 42.97, lng: 1.52 },
  "10": { lat: 48.3, lng: 4.18 },
  "11": { lat: 43.12, lng: 2.33 },
  "12": { lat: 44.29, lng: 2.6 },
  "13": { lat: 43.56, lng: 5.23 },
  "14": { lat: 49.15, lng: -0.32 },
  "15": { lat: 45.05, lng: 2.65 },
  "16": { lat: 45.7, lng: 0.15 },
  "17": { lat: 45.77, lng: -0.65 },
  "18": { lat: 47.03, lng: 2.52 },
  "19": { lat: 45.31, lng: 1.84 },
  // 2A et 2B sont normalisés en "20" à la saisie (codes postaux corses commencent par 20)
  "20": { lat: 42.17, lng: 9.11 },
  "21": { lat: 47.38, lng: 4.79 },
  "22": { lat: 48.48, lng: -2.86 },
  "23": { lat: 46.09, lng: 2.03 },
  "24": { lat: 45.06, lng: 0.74 },
  "25": { lat: 47.23, lng: 6.37 },
  "26": { lat: 44.69, lng: 5.13 },
  "27": { lat: 49.14, lng: 1 },
  "28": { lat: 48.44, lng: 1.39 },
  "29": { lat: 48.29, lng: -4.14 },
  "30": { lat: 44.02, lng: 4.2 },
  "31": { lat: 43.33, lng: 1.14 },
  "32": { lat: 43.66, lng: 0.46 },
  "33": { lat: 44.82, lng: -0.34 },
  "34": { lat: 43.59, lng: 3.42 },
  "35": { lat: 48.19, lng: -1.63 },
  "36": { lat: 46.77, lng: 1.61 },
  "37": { lat: 47.26, lng: 0.66 },
  "38": { lat: 45.32, lng: 5.49 },
  "39": { lat: 46.77, lng: 5.68 },
  "40": { lat: 43.81, lng: -0.73 },
  "41": { lat: 47.65, lng: 1.31 },
  "42": { lat: 45.72, lng: 4.2 },
  "43": { lat: 45.14, lng: 3.76 },
  "44": { lat: 47.34, lng: -1.68 },
  "45": { lat: 47.96, lng: 2.34 },
  "46": { lat: 44.64, lng: 1.63 },
  "47": { lat: 44.39, lng: 0.48 },
  "48": { lat: 44.55, lng: 3.49 },
  "49": { lat: 47.38, lng: -0.49 },
  "50": { lat: 49.11, lng: -1.34 },
  "51": { lat: 48.96, lng: 4.19 },
  "52": { lat: 48.11, lng: 5.26 },
  "53": { lat: 48.15, lng: -0.67 },
  "54": { lat: 48.78, lng: 6.17 },
  "55": { lat: 49.02, lng: 5.38 },
  "56": { lat: 47.81, lng: -2.78 },
  "57": { lat: 49.05, lng: 6.62 },
  "58": { lat: 47.14, lng: 3.49 },
  "59": { lat: 50.42, lng: 3.26 },
  "60": { lat: 49.43, lng: 2.42 },
  "61": { lat: 48.64, lng: 0.1 },
  "62": { lat: 50.46, lng: 2.34 },
  "63": { lat: 45.73, lng: 3.17 },
  "64": { lat: 43.32, lng: -0.66 },
  "65": { lat: 43.14, lng: 0.21 },
  "66": { lat: 42.61, lng: 2.56 },
  "67": { lat: 48.68, lng: 7.55 },
  "68": { lat: 47.8, lng: 7.27 },
  "69": { lat: 45.85, lng: 4.66 },
  "70": { lat: 47.63, lng: 6.1 },
  "71": { lat: 46.62, lng: 4.62 },
  "72": { lat: 48.04, lng: 0.23 },
  "73": { lat: 45.54, lng: 6.18 },
  "74": { lat: 46.07, lng: 6.3 },
  "75": { lat: 48.86, lng: 2.35 },
  "76": { lat: 49.67, lng: 0.98 },
  "77": { lat: 48.66, lng: 2.92 },
  "78": { lat: 48.85, lng: 1.84 },
  "79": { lat: 46.5, lng: -0.3 },
  "80": { lat: 49.94, lng: 2.31 },
  "81": { lat: 43.8, lng: 2.11 },
  "82": { lat: 44.06, lng: 1.22 },
  "83": { lat: 43.43, lng: 6.22 },
  "84": { lat: 44.01, lng: 5.16 },
  "85": { lat: 46.65, lng: -1.25 },
  "86": { lat: 46.61, lng: 0.41 },
  "87": { lat: 45.89, lng: 1.24 },
  "88": { lat: 48.23, lng: 6.32 },
  "89": { lat: 47.85, lng: 3.61 },
  "90": { lat: 47.63, lng: 6.93 },
  "91": { lat: 48.55, lng: 2.26 },
  "92": { lat: 48.84, lng: 2.25 },
  "93": { lat: 48.91, lng: 2.47 },
  "94": { lat: 48.78, lng: 2.46 },
  "95": { lat: 49.08, lng: 2.15 },
};

const ARTICLES_FR = new Set([
  "de",
  "du",
  "des",
  "d'",
  "le",
  "la",
  "les",
  "l'",
  "en",
  "au",
  "aux",
  "et",
  "à",
  "sur",
  "sous",
]);

// Convertit un nom tout-majuscule en title case français.
// Les articles courants restent en minuscules sauf en début de chaîne.
// N'est appliqué que si la chaîne est entièrement en majuscules.
function normaliserCasse(nom: string): string {
  if (nom !== nom.toUpperCase()) return nom;
  const mots = nom.split(/(\s+|-)/);
  let debutPhrase = true;
  return mots
    .map((mot) => {
      if (/^\s+$/.test(mot) || mot === "-") {
        debutPhrase = mot === "-";
        return mot;
      }
      const bas = mot.toLowerCase();
      if (!debutPhrase && ARTICLES_FR.has(bas)) return bas;
      debutPhrase = false;
      return bas.replace(
        /(^|['''])([a-z])/g,
        (_, sep, c) => sep + c.toUpperCase(),
      );
    })
    .join("");
}

export function extraireDept(codePostal: string | undefined): string | null {
  if (!codePostal) return null;
  if (codePostal.startsWith("97")) return codePostal.slice(0, 3);
  return codePostal.slice(0, 2);
}

export function parseDateTM(
  dateStr: string,
  heureStr: string | undefined,
): { date: string; heure: string | null } {
  const date = dateStr.slice(0, 10);
  if (!heureStr) return { date, heure: null };
  return { date, heure: heureStr.slice(0, 5) };
}

// artisteFallback : nom de l'artiste cherché (mot-clé de fetchArtiste).
// Utilisé quand _embedded.attractions est absent, à condition que le titre
// contienne le nom — sinon l'événement est rejeté (faux positif keyword TM).
// Sans fallback (fetch:region, fetchArtistesBatch) : null si pas d'attraction.
export function mapEvenement(
  evt: Record<string, unknown>,
  artisteFallback?: string,
): ConcertBrut | null {
  try {
    const dates = evt["dates"] as Record<string, unknown>;
    const start = dates?.["start"] as Record<string, unknown>;
    const dateStr = start?.["localDate"] as string | undefined;
    if (!dateStr) return null;

    const { date, heure } = parseDateTM(
      dateStr,
      start?.["localTime"] as string | undefined,
    );

    const embedded = evt["_embedded"] as Record<string, unknown>;
    const attractions = embedded?.["attractions"] as
      | Record<string, unknown>[]
      | undefined;
    const nomAttractionPrincipal = attractions?.[0]?.["name"] as
      | string
      | undefined;
    const nomEvenement = evt["name"] as string;
    let artiste: string;
    if (nomAttractionPrincipal) {
      artiste = nomAttractionPrincipal;
    } else if (artisteFallback) {
      // Faux positif TM : keyword search trop large, titre sans rapport avec l'artiste cherché
      if (!nomEvenement.toLowerCase().includes(artisteFallback.toLowerCase())) {
        return null;
      }
      artiste = artisteFallback;
    } else {
      // Sans fallback (fetch:region, fetchArtistesBatch) : sans attraction, le nom est inconnu
      return null;
    }

    const venues = embedded?.["venues"] as
      | Record<string, unknown>[]
      | undefined;
    const venue = venues?.[0];
    const salle = normaliserCasse(
      (venue?.["name"] as string | undefined) ?? "Salle inconnue",
    );
    const ville =
      (venue?.["city"] as Record<string, string> | undefined)?.["name"] ?? null;
    const cp = venue?.["postalCode"] as string | undefined;
    const dept = extraireDept(cp);

    const priceRanges = evt["priceRanges"] as
      | Record<string, number>[]
      | undefined;
    const prixMin = priceRanges?.[0]?.["min"] ?? null;
    const prixMax = priceRanges?.[0]?.["max"] ?? null;

    const url = (evt["url"] as string | undefined) ?? null;

    const datesData = evt["dates"] as Record<string, unknown> | undefined;
    const statut =
      (datesData?.["status"] as Record<string, string> | undefined)?.["code"] ??
      null;

    const classificationsData = evt["classifications"] as
      | Record<string, unknown>[]
      | undefined;
    const segmentNom =
      (
        classificationsData?.[0]?.["segment"] as
          | Record<string, string>
          | undefined
      )?.["name"]?.toLowerCase() ?? null;
    // Normaliser "arts & theatre" → "theatre"
    const classification =
      segmentNom === "arts & theatre" ? "theatre" : segmentNom;
    const genre =
      (
        classificationsData?.[0]?.["genre"] as
          | Record<string, string>
          | undefined
      )?.["name"] ?? null;

    return {
      artiste,
      date,
      heure,
      salle,
      ville,
      dept,
      prixMin: prixMin !== null ? Number(prixMin) : null,
      prixMax: prixMax !== null ? Number(prixMax) : null,
      url,
      source: "ticketmaster",
      classification,
      genre,
      statut,
    };
  } catch (err) {
    logger.warn({ err }, "erreur parsing événement TM");
    return null;
  }
}

async function fetchPage(
  params: URLSearchParams,
  apiKey: string,
  artisteFallback?: string,
): Promise<{ events: ConcertBrut[]; total: number; quota: QuotaTM | null }> {
  params.set("apikey", apiKey);
  params.set("locale", "fr");
  params.set("countryCode", "FR");

  const url = `${BASE_URL}/events.json?${params.toString()}`;
  const res = await fetch(url);

  // Extraire le quota depuis les headers TM (présents même sur les erreurs)
  const quota: QuotaTM | null = res.headers.has("Rate-Limit-Available")
    ? {
        dispo: parseInt(res.headers.get("Rate-Limit-Available") ?? "0"),
        total: parseInt(res.headers.get("Rate-Limit") ?? "0"),
        reset: parseInt(res.headers.get("Rate-Limit-Reset") ?? "0"),
        misAJour: new Date().toISOString(),
      }
    : null;

  if (!res.ok) {
    // 429 = rate limit attendu, 5xx = erreur serveur TM — les deux sont récupérables
    logger.warn({ status: res.status, url }, "erreur HTTP Ticketmaster");
    return { events: [], total: 0, quota };
  }

  const data = (await res.json()) as Record<string, unknown>;
  const page = data["page"] as Record<string, number> | undefined;
  const total = page?.["totalElements"] ?? 0;

  const embedded = data["_embedded"] as Record<string, unknown> | undefined;
  const evts = embedded?.["events"] as Record<string, unknown>[] | undefined;
  if (!evts) return { events: [], total, quota };

  const events = evts
    .map((evt) => mapEvenement(evt, artisteFallback))
    .filter((e): e is ConcertBrut => e !== null);
  return { events, total, quota };
}

// Récupère toutes les pages d'un appel TM jusqu'à NB_PAGES_MAX.
// Utiliser uniquement pour les appels groupés (région, batch) où le volume peut dépasser TAILLE_PAGE.
async function fetchToutesPages(
  params: URLSearchParams,
  apiKey: string,
  artisteFallback?: string,
): Promise<{ events: ConcertBrut[]; quota: QuotaTM | null }> {
  const tousLesEvents: ConcertBrut[] = [];
  let quota: QuotaTM | null = null;
  let totalElements = 0;

  for (let numPage = 0; numPage < NB_PAGES_MAX; numPage++) {
    const paramsPage = new URLSearchParams(params);
    paramsPage.set("page", String(numPage));

    const {
      events,
      total,
      quota: q,
    } = await fetchPage(paramsPage, apiKey, artisteFallback);
    if (q) quota = q;
    if (numPage === 0) totalElements = total;
    tousLesEvents.push(...events);

    // Arrêter si la page est vide ou si on a tout récupéré
    if (events.length === 0 || tousLesEvents.length >= totalElements) break;

    // Respecter le rate limit TM (5 req/s) entre les pages
    await new Promise((r) => setTimeout(r, 250));
  }

  return { events: tousLesEvents, quota };
}

export class TicketmasterSource implements SourceConcerts {
  private _dernierQuota: QuotaTM | null = null;

  constructor(private readonly apiKey: string) {}

  obtenirQuota(): QuotaTM | null {
    return this._dernierQuota;
  }

  async fetchArtiste(nom: string): Promise<ConcertBrut[]> {
    const params = new URLSearchParams({
      keyword: nom,
      classificationName: CLASSIFICATIONS.join(","),
      size: String(TAILLE_PAGE),
      sort: "date,asc",
    });

    const { events, total, quota } = await fetchPage(params, this.apiKey, nom);
    if (quota) this._dernierQuota = quota;
    logger.info({ artiste: nom, total }, "TM fetchArtiste");
    return events;
  }

  async fetchSalle(venueId: string): Promise<ConcertBrut[]> {
    const params = new URLSearchParams({
      venueId,
      size: String(TAILLE_PAGE),
      sort: "date,asc",
    });

    const { events, total, quota } = await fetchPage(params, this.apiKey);
    if (quota) this._dernierQuota = quota;
    logger.info({ venueId, total }, "TM fetchSalle");
    return events;
  }

  async rechercherAttractionId(nom: string): Promise<string | null> {
    const params = new URLSearchParams({
      keyword: nom,
      locale: "*",
      size: "1",
      apikey: this.apiKey,
    });
    const url = `${BASE_URL}/attractions.json?${params.toString()}`;
    try {
      const res = await fetch(url);
      if (!res.ok) return null;
      const data = (await res.json()) as Record<string, unknown>;
      const embedded = data["_embedded"] as Record<string, unknown> | undefined;
      const attractions = embedded?.["attractions"] as
        | Record<string, unknown>[]
        | undefined;
      return (attractions?.[0]?.["id"] as string) ?? null;
    } catch {
      return null;
    }
  }

  async fetchArtistesBatch(attractionIds: string[]): Promise<ConcertBrut[]> {
    if (attractionIds.length === 0) return [];
    const params = new URLSearchParams({
      attractionId: attractionIds.join(","),
      size: String(TAILLE_PAGE_REGION),
      sort: "date,asc",
    });
    const { events, quota } = await fetchToutesPages(params, this.apiKey);
    if (quota) this._dernierQuota = quota;
    logger.info(
      { ids: attractionIds.length, total: events.length },
      "TM fetchArtistesBatch",
    );
    return events;
  }

  async fetchRegion(
    depts: string[],
    classifs: string[] = CLASSIFICATIONS,
    rayonKm = 50,
  ): Promise<ConcertBrut[]> {
    const tous: ConcertBrut[] = [];

    for (const dept of depts) {
      const centre = CENTRES_DEPT[dept];
      if (!centre) {
        logger.warn({ dept }, "département sans coordonnées connues, ignoré");
        continue;
      }

      const params = new URLSearchParams({
        latlong: `${centre.lat},${centre.lng}`,
        radius: String(rayonKm),
        unit: "km",
        classificationName: classifs.join(","),
        size: String(TAILLE_PAGE_REGION),
        sort: "date,asc",
      });

      const { events, quota } = await fetchToutesPages(params, this.apiKey);
      if (quota) this._dernierQuota = quota;
      tous.push(...events);

      // Respecter le rate limit TM (5 req/s)
      await new Promise((r) => setTimeout(r, 250));
    }

    return tous;
  }
}
