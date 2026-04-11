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

export interface QuotaTM {
  dispo: number;
  total: number;
  reset: number; // Unix timestamp ms
  misAJour: string; // ISO 8601
}

// Correspondance code département → latitude/longitude centre
const CENTRES_DEPT: Record<string, { lat: number; lng: number }> = {
  "01": { lat: 46.20, lng: 5.23 },
  "02": { lat: 49.57, lng: 3.62 },
  "03": { lat: 46.56, lng: 3.33 },
  "04": { lat: 44.09, lng: 6.24 },
  "05": { lat: 44.56, lng: 6.08 },
  "06": { lat: 43.90, lng: 7.24 },
  "07": { lat: 44.74, lng: 4.60 },
  "08": { lat: 49.77, lng: 4.72 },
  "09": { lat: 42.97, lng: 1.60 },
  "10": { lat: 48.30, lng: 4.08 },
  "11": { lat: 43.21, lng: 2.35 },
  "12": { lat: 44.35, lng: 2.57 },
  "13": { lat: 43.53, lng: 5.45 },
  "14": { lat: 49.18, lng: -0.36 },
  "15": { lat: 44.93, lng: 2.44 },
  "16": { lat: 45.65, lng: 0.16 },
  "17": { lat: 46.16, lng: -1.15 },
  "18": { lat: 47.08, lng: 2.40 },
  "19": { lat: 45.27, lng: 1.77 },
  // 2A et 2B sont normalisés en "20" à la saisie (codes postaux corses commencent par 20)
  "20": { lat: 42.04, lng: 9.01 },
  "21": { lat: 47.31, lng: 5.04 },
  "22": { lat: 48.51, lng: -2.77 },
  "23": { lat: 46.17, lng: 1.87 },
  "24": { lat: 45.19, lng: 0.72 },
  "25": { lat: 47.24, lng: 6.02 },
  "26": { lat: 44.93, lng: 4.89 },
  "27": { lat: 49.02, lng: 1.15 },
  "28": { lat: 48.45, lng: 1.49 },
  "29": { lat: 47.99, lng: -4.10 },
  "30": { lat: 43.84, lng: 4.36 },
  "31": { lat: 43.60, lng: 1.44 },
  "32": { lat: 43.65, lng: 0.59 },
  "33": { lat: 44.84, lng: -0.58 },
  "34": { lat: 43.61, lng: 3.88 },
  "35": { lat: 48.11, lng: -1.68 },
  "36": { lat: 46.81, lng: 1.69 },
  "37": { lat: 47.39, lng: 0.69 },
  "38": { lat: 45.19, lng: 5.72 },
  "39": { lat: 46.67, lng: 5.56 },
  "40": { lat: 43.89, lng: -0.50 },
  "41": { lat: 47.59, lng: 1.33 },
  "42": { lat: 45.44, lng: 4.39 },
  "43": { lat: 45.04, lng: 3.88 },
  "44": { lat: 47.22, lng: -1.55 },
  "45": { lat: 47.90, lng: 1.90 },
  "46": { lat: 44.45, lng: 1.44 },
  "47": { lat: 44.20, lng: 0.62 },
  "48": { lat: 44.52, lng: 3.50 },
  "49": { lat: 47.47, lng: -0.55 },
  "50": { lat: 49.11, lng: -1.09 },
  "51": { lat: 49.04, lng: 4.03 },
  "52": { lat: 48.11, lng: 5.14 },
  "53": { lat: 48.07, lng: -0.77 },
  "54": { lat: 48.69, lng: 6.18 },
  "55": { lat: 48.77, lng: 5.16 },
  "56": { lat: 47.66, lng: -2.76 },
  "57": { lat: 49.12, lng: 6.18 },
  "58": { lat: 46.99, lng: 3.16 },
  "59": { lat: 50.34, lng: 3.19 },
  "60": { lat: 49.42, lng: 2.08 },
  "61": { lat: 48.43, lng: 0.09 },
  "62": { lat: 50.53, lng: 2.63 },
  "63": { lat: 45.78, lng: 3.08 },
  "64": { lat: 43.30, lng: -0.37 },
  "65": { lat: 43.23, lng: 0.07 },
  "66": { lat: 42.70, lng: 2.90 },
  "67": { lat: 48.58, lng: 7.75 },
  "68": { lat: 48.08, lng: 7.36 },
  "69": { lat: 45.75, lng: 4.85 },
  "70": { lat: 47.62, lng: 6.15 },
  "71": { lat: 46.31, lng: 4.83 },
  "72": { lat: 48.00, lng: 0.20 },
  "73": { lat: 45.57, lng: 5.92 },
  "74": { lat: 45.90, lng: 6.12 },
  "75": { lat: 48.86, lng: 2.35 },
  "76": { lat: 49.44, lng: 1.10 },
  "77": { lat: 48.54, lng: 2.66 },
  "78": { lat: 48.80, lng: 1.99 },
  "79": { lat: 46.32, lng: -0.46 },
  "80": { lat: 49.90, lng: 2.30 },
  "81": { lat: 43.93, lng: 2.15 },
  "82": { lat: 44.02, lng: 1.35 },
  "83": { lat: 43.47, lng: 6.22 },
  "84": { lat: 43.95, lng: 4.81 },
  "85": { lat: 46.67, lng: -1.43 },
  "86": { lat: 46.58, lng: 0.34 },
  "87": { lat: 45.83, lng: 1.26 },
  "88": { lat: 48.17, lng: 6.45 },
  "89": { lat: 47.80, lng: 3.57 },
  "90": { lat: 47.64, lng: 6.86 },
  "91": { lat: 48.63, lng: 2.23 },
  "92": { lat: 48.83, lng: 2.25 },
  "93": { lat: 48.91, lng: 2.49 },
  "94": { lat: 48.78, lng: 2.47 },
  "95": { lat: 49.05, lng: 2.08 },
};

function extraireDept(codePostal: string | undefined): string | null {
  if (!codePostal) return null;
  if (codePostal.startsWith("97")) return codePostal.slice(0, 3);
  return codePostal.slice(0, 2);
}

function parseDateTM(
  dateStr: string,
  heureStr: string | undefined,
): { date: string; heure: string | null } {
  const date = dateStr.slice(0, 10);
  if (!heureStr) return { date, heure: null };
  return { date, heure: heureStr.slice(0, 5) };
}

function mapEvenement(evt: Record<string, unknown>): ConcertBrut | null {
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
    const artiste =
      (attractions?.[0]?.["name"] as string | undefined) ??
      (evt["name"] as string);
    if (!artiste) return null;

    const venues = embedded?.["venues"] as
      | Record<string, unknown>[]
      | undefined;
    const venue = venues?.[0];
    const salle = (venue?.["name"] as string | undefined) ?? "Salle inconnue";
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
    logger.error({ status: res.status, url }, "erreur HTTP Ticketmaster");
    return { events: [], total: 0, quota };
  }

  const data = (await res.json()) as Record<string, unknown>;
  const page = data["page"] as Record<string, number> | undefined;
  const total = page?.["totalElements"] ?? 0;

  const embedded = data["_embedded"] as Record<string, unknown> | undefined;
  const evts = embedded?.["events"] as Record<string, unknown>[] | undefined;
  if (!evts) return { events: [], total, quota };

  const events = evts
    .map(mapEvenement)
    .filter((e): e is ConcertBrut => e !== null);
  return { events, total, quota };
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

    const { events, total, quota } = await fetchPage(params, this.apiKey);
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
      size: String(TAILLE_PAGE),
      sort: "date,asc",
    });
    const { events, quota } = await fetchPage(params, this.apiKey);
    if (quota) this._dernierQuota = quota;
    logger.info({ ids: attractionIds.length }, "TM fetchArtistesBatch");
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
        size: String(TAILLE_PAGE),
        sort: "date,asc",
      });

      const { events, quota } = await fetchPage(params, this.apiKey);
      if (quota) this._dernierQuota = quota;
      tous.push(...events);

      // Respecter le rate limit TM (5 req/s)
      await new Promise((r) => setTimeout(r, 250));
    }

    return tous;
  }
}
