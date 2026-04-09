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
  "01": { lat: 46.2, lng: 5.23 },
  "02": { lat: 49.57, lng: 3.62 },
  "03": { lat: 46.56, lng: 3.33 },
  "06": { lat: 43.9, lng: 7.24 },
  "13": { lat: 43.53, lng: 5.45 },
  "14": { lat: 49.18, lng: -0.36 },
  "21": { lat: 47.31, lng: 5.04 },
  "25": { lat: 47.24, lng: 6.02 },
  "31": { lat: 43.6, lng: 1.44 },
  "33": { lat: 44.84, lng: -0.58 },
  "34": { lat: 43.61, lng: 3.88 },
  "35": { lat: 48.11, lng: -1.68 },
  "38": { lat: 45.19, lng: 5.72 },
  "44": { lat: 47.22, lng: -1.55 },
  "45": { lat: 47.9, lng: 1.9 },
  "49": { lat: 47.47, lng: -0.55 },
  "51": { lat: 49.04, lng: 4.03 },
  "54": { lat: 48.69, lng: 6.18 },
  "57": { lat: 49.12, lng: 6.18 },
  "59": { lat: 50.34, lng: 3.19 },
  "60": { lat: 49.42, lng: 2.08 },
  "62": { lat: 50.53, lng: 2.63 },
  "63": { lat: 45.78, lng: 3.08 },
  "67": { lat: 48.58, lng: 7.75 },
  "69": { lat: 45.75, lng: 4.85 },
  "75": { lat: 48.86, lng: 2.35 },
  "76": { lat: 49.44, lng: 1.1 },
  "77": { lat: 48.54, lng: 2.66 },
  "78": { lat: 48.8, lng: 1.99 },
  "80": { lat: 49.9, lng: 2.3 },
  "83": { lat: 43.47, lng: 6.22 },
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
      (datesData?.["status"] as Record<string, string> | undefined)?.[
        "code"
      ] ?? null;

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
        radius: "50",
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
