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
// Taille maximale autorisée par TM (region, batch artistes, salle, artiste individuel)
const TAILLE_PAGE_REGION = 200;
// Protection quota : ne jamais dépasser 5 pages par appel (max 1 000 concerts)
const NB_PAGES_MAX = 5;

export interface QuotaTM {
  dispo: number;
  total: number;
  reset: number; // Unix timestamp ms
  misAJour: string; // ISO 8601
}

// Correspondance code département → centre (lat/lng) et rayon de couverture.
// Rayon = demi-diagonale du bounding box OSM (garantit la couverture complète quelle que soit la forme).
// 2A et 2B sont normalisés en "20" à la saisie (codes postaux corses commencent par 20).
// Source : Nominatim / OpenStreetMap — données stables, régénérables via scripts/fetch-bboxes-depts.mjs
const CENTRES_DEPT: Record<
  string,
  { lat: number; lng: number; rayon: number }
> = {
  "01": { lat: 46.0654, lng: 5.4489, rayon: 76 },
  "02": { lat: 49.4533, lng: 3.6069, rayon: 83 },
  "03": { lat: 46.3674, lng: 3.1412, rayon: 83 },
  "04": { lat: 44.1641, lng: 6.2328, rayon: 81 },
  "05": { lat: 44.6565, lng: 6.2478, rayon: 84 },
  "06": { lat: 43.9213, lng: 7.1771, rayon: 66 },
  "07": { lat: 44.8153, lng: 4.3737, rayon: 74 },
  "08": { lat: 49.6981, lng: 4.7093, rayon: 72 },
  "09": { lat: 42.9447, lng: 1.5013, rayon: 69 },
  "10": { lat: 48.3201, lng: 4.1244, rayon: 71 },
  "11": { lat: 43.0543, lng: 2.4646, rayon: 78 },
  "12": { lat: 44.316, lng: 2.6459, rayon: 95 },
  "13": { lat: 43.541, lng: 5.0218, rayon: 77 },
  "14": { lat: 49.0908, lng: -0.3566, rayon: 70 },
  "15": { lat: 45.0497, lng: 2.7171, rayon: 71 },
  "16": { lat: 45.6667, lng: 0.2419, rayon: 76 },
  "17": { lat: 45.7302, lng: -0.7783, rayon: 94 },
  "18": { lat: 47.0248, lng: 2.4266, rayon: 84 },
  "19": { lat: 45.3429, lng: 1.8778, rayon: 70 },
  "20": { lat: 42.1804, lng: 9.0474, rayon: 104 },
  "21": { lat: 47.4655, lng: 4.7921, rayon: 84 },
  "22": { lat: 48.5728, lng: -2.7875, rayon: 89 },
  "23": { lat: 46.0594, lng: 1.992, rayon: 65 },
  "24": { lat: 45.1429, lng: 0.7031, rayon: 87 },
  "25": { lat: 47.067, lng: 6.3806, rayon: 77 },
  "26": { lat: 44.7296, lng: 5.2386, rayon: 83 },
  "27": { lat: 49.0756, lng: 1.0495, rayon: 72 },
  "28": { lat: 48.4475, lng: 1.3751, rayon: 72 },
  "29": { lat: 48.2292, lng: -4.2653, rayon: 88 },
  "30": { lat: 43.9599, lng: 4.0537, rayon: 85 },
  "31": { lat: 43.3054, lng: 1.2451, rayon: 95 },
  "32": { lat: 43.6954, lng: 0.4604, rayon: 74 },
  "33": { lat: 44.8979, lng: -0.4752, rayon: 100 },
  "34": { lat: 43.5915, lng: 3.3671, rayon: 79 },
  "35": { lat: 48.1767, lng: -1.6523, rayon: 77 },
  "36": { lat: 46.8122, lng: 1.5358, rayon: 73 },
  "37": { lat: 47.2232, lng: 0.7094, rayon: 74 },
  "38": { lat: 45.2898, lng: 5.5503, rayon: 92 },
  "39": { lat: 46.7834, lng: 5.7297, rayon: 69 },
  "40": { lat: 44.0099, lng: -0.6935, rayon: 89 },
  "41": { lat: 47.6598, lng: 1.4143, rayon: 82 },
  "42": { lat: 45.7538, lng: 4.2246, rayon: 72 },
  "43": { lat: 45.0857, lng: 3.7863, rayon: 67 },
  "44": { lat: 47.3481, lng: -1.7875, rayon: 84 },
  "45": { lat: 47.914, lng: 2.3204, rayon: 77 },
  "46": { lat: 44.625, lng: 1.5963, rayon: 68 },
  "47": { lat: 44.3692, lng: 0.4689, rayon: 66 },
  "48": { lat: 44.5426, lng: 3.4903, rayon: 63 },
  "49": { lat: 47.3893, lng: -0.5599, rayon: 76 },
  "50": { lat: 49.0919, lng: -1.3449, rayon: 84 },
  "51": { lat: 48.9613, lng: 4.218, rayon: 78 },
  "52": { lat: 48.1329, lng: 5.2592, rayon: 78 },
  "53": { lat: 48.1511, lng: -0.6439, rayon: 64 },
  "54": { lat: 48.9561, lng: 6.2746, rayon: 92 },
  "55": { lat: 49.013, lng: 5.3713, rayon: 76 },
  "56": { lat: 47.7443, lng: -2.8853, rayon: 82 },
  "57": { lat: 49.0207, lng: 6.7653, rayon: 84 },
  "58": { lat: 47.1197, lng: 3.5381, rayon: 74 },
  "59": { lat: 50.529, lng: 3.1551, rayon: 99 },
  "60": { lat: 49.4121, lng: 2.4273, rayon: 67 },
  "61": { lat: 48.576, lng: 0.058, rayon: 81 },
  "62": { lat: 50.5143, lng: 2.3718, rayon: 80 },
  "63": { lat: 45.7717, lng: 3.1868, rayon: 82 },
  "64": { lat: 43.1872, lng: -0.8804, rayon: 87 },
  "65": { lat: 43.1438, lng: 0.1595, rayon: 66 },
  "66": { lat: 42.6257, lng: 2.4506, rayon: 68 },
  "67": { lat: 48.5991, lng: 7.5866, rayon: 72 },
  "68": { lat: 47.8658, lng: 7.2317, rayon: 58 },
  "69": { lat: 45.8803, lng: 4.702, rayon: 60 },
  "70": { lat: 47.6384, lng: 6.0962, rayon: 70 },
  "71": { lat: 46.6557, lng: 4.5439, rayon: 90 },
  "72": { lat: 48.0267, lng: 0.2343, rayon: 72 },
  "73": { lat: 45.4949, lng: 6.4039, rayon: 79 },
  "74": { lat: 46.069, lng: 6.4252, rayon: 65 },
  "75": { lat: 48.8589, lng: 2.3469, rayon: 11 },
  "76": { lat: 49.6614, lng: 0.9283, rayon: 77 },
  "77": { lat: 48.619, lng: 2.9758, rayon: 70 },
  "78": { lat: 48.762, lng: 1.8377, rayon: 46 },
  "79": { lat: 46.539, lng: -0.3416, rayon: 77 },
  "80": { lat: 49.9691, lng: 2.2913, rayon: 79 },
  "81": { lat: 43.7922, lng: 2.2363, rayon: 73 },
  "82": { lat: 44.0807, lng: 1.3693, rayon: 62 },
  "83": { lat: 43.3954, lng: 6.2946, rayon: 69 },
  "84": { lat: 44.0451, lng: 5.2035, rayon: 62 },
  "85": { lat: 46.6758, lng: -1.4694, rayon: 85 },
  "86": { lat: 46.6121, lng: 0.5541, rayon: 81 },
  "87": { lat: 45.919, lng: 1.2699, rayon: 73 },
  "88": { lat: 48.1637, lng: 6.296, rayon: 78 },
  "89": { lat: 47.8552, lng: 3.5943, rayon: 83 },
  "90": { lat: 47.6292, lng: 6.9499, rayon: 27 },
  "91": { lat: 48.5304, lng: 2.2499, rayon: 37 },
  "92": { lat: 48.8402, lng: 2.2413, rayon: 15 },
  "93": { lat: 48.9098, lng: 2.4458, rayon: 17 },
  "94": { lat: 48.7745, lng: 2.4622, rayon: 15 },
  "95": { lat: 49.0751, lng: 2.1014, rayon: 41 },
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

const CLASSIFICATION_MAP: Record<string, string> = {
  musique: "music",
  opéra: "opera",
  "comédie musicale": "musical",
  "arts et théâtre": "theatre",
};

function normaliserClassification(nom: string | null): string | null {
  if (!nom) return null;
  return CLASSIFICATION_MAP[nom] ?? nom;
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
      artiste = normaliserCasse(nomAttractionPrincipal);
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
    const classification = normaliserClassification(segmentNom);
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
    // 429 = rate limit attendu, 5xx = erreur serveur TM — les deux sont récupérables.
    // Ne pas propager le quota : sur 429, TM retourne Rate-Limit: 5 (limite/s, pas quota/j),
    // ce qui polluerait Redis avec des valeurs aberrantes (total:5, dispo:0).
    logger.warn(
      { status: res.status, url, dispo: quota?.dispo, total: quota?.total },
      "erreur HTTP Ticketmaster",
    );
    return { events: [], total: 0, quota: null };
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

function obtenirFenetreFetch(): { startDateTime: string; endDateTime: string } {
  const fmt = (d: Date) => d.toISOString().replace(/\.\d{3}Z$/, "Z");
  const maintenant = new Date();
  const dans6Mois = new Date(maintenant);
  dans6Mois.setMonth(dans6Mois.getMonth() + 6);
  return { startDateTime: fmt(maintenant), endDateTime: fmt(dans6Mois) };
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

  if (tousLesEvents.length > 0 && tousLesEvents.length < totalElements) {
    logger.warn(
      { total: totalElements, recuperes: tousLesEvents.length },
      "NB_PAGES_MAX atteint — concerts manqués",
    );
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
      size: String(TAILLE_PAGE_REGION),
      sort: "date,asc",
    });

    const { events, total, quota } = await fetchPage(params, this.apiKey, nom);
    if (quota) this._dernierQuota = quota;
    logger.info({ artiste: nom, total }, "TM fetchArtiste");
    return events;
  }

  async fetchSalle(venueId: string): Promise<ConcertBrut[]> {
    const { startDateTime, endDateTime } = obtenirFenetreFetch();
    const params = new URLSearchParams({
      venueId,
      size: String(TAILLE_PAGE_REGION),
      sort: "date,asc",
      startDateTime,
      endDateTime,
    });

    const { events, quota } = await fetchToutesPages(params, this.apiKey);
    if (quota) this._dernierQuota = quota;
    logger.info({ venueId, total: events.length }, "TM fetchSalle");
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
  ): Promise<ConcertBrut[]> {
    const tous: ConcertBrut[] = [];

    for (const dept of depts) {
      const centre = CENTRES_DEPT[dept];
      if (!centre) {
        logger.warn({ dept }, "département sans coordonnées connues, ignoré");
        continue;
      }

      const { startDateTime, endDateTime } = obtenirFenetreFetch();
      const params = new URLSearchParams({
        latlong: `${centre.lat},${centre.lng}`,
        radius: String(centre.rayon),
        unit: "km",
        classificationName: classifs.join(","),
        size: String(TAILLE_PAGE_REGION),
        sort: "date,asc",
        startDateTime,
        endDateTime,
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
