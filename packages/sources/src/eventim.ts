import pino from "pino";
import type { ConcertBrut, SourceConcerts } from "./types.js";

const logger = pino({ name: "source:eventim" });

const BASE_URL =
  "https://public-api.eventim.com/websearch/search/api/exploration";
const WEB_ID = "web__eventim-fr";
const TAILLE_PAGE = 50;
const MAX_PAGES = 10;

function extraireDept(codePostal: string | null | undefined): string | null {
  if (!codePostal) return null;
  const cp = String(codePostal).trim();
  if (cp.startsWith("97")) return cp.slice(0, 3);
  return cp.slice(0, 2) || null;
}

function parseDateEventim(dateStr: string): {
  date: string;
  heure: string | null;
} {
  const datePart = dateStr.slice(0, 10);
  const tIndex = dateStr.indexOf("T");
  if (tIndex === -1) return { date: datePart, heure: null };
  const heurePart = dateStr.slice(tIndex + 1, tIndex + 6);
  const heure = heurePart !== "00:00" ? heurePart : null;
  return { date: datePart, heure };
}

function mapProduit(produit: Record<string, unknown>): ConcertBrut | null {
  try {
    // Structure : typeAttributes.liveEntertainment.{startDate, location}
    const liveEnt = (
      produit["typeAttributes"] as Record<string, unknown> | undefined
    )?.["liveEntertainment"] as Record<string, unknown> | undefined;

    const dateStr = liveEnt?.["startDate"] as string | undefined;
    if (!dateStr) return null;

    const { date, heure } = parseDateEventim(dateStr);

    const artiste = produit["name"] as string | undefined;
    if (!artiste) return null;

    const location = liveEnt?.["location"] as
      | Record<string, unknown>
      | undefined;
    const salle =
      (location?.["name"] as string | undefined) ?? "Salle inconnue";
    const ville = (location?.["city"] as string | undefined) ?? null;
    const cp = location?.["postalCode"] as string | undefined;
    const dept = extraireDept(cp);

    const priceRange = liveEnt?.["priceRange"] as
      | Record<string, unknown>
      | undefined;
    const prixMin = (priceRange?.["min"] as number | undefined) ?? null;
    const prixMax = (priceRange?.["max"] as number | undefined) ?? null;

    // URL : préférer le lien direct sinon reconstruire depuis url.domain + url.path
    const urlObj = produit["url"] as Record<string, unknown> | undefined;
    const lienDirect = produit["link"] as string | undefined;
    let url: string | null = null;
    if (lienDirect?.startsWith("http")) {
      url = lienDirect;
    } else if (urlObj?.["domain"] && urlObj?.["path"]) {
      url = `${urlObj["domain"]}${urlObj["path"]}`;
    }

    return {
      artiste,
      date,
      heure,
      salle,
      ville,
      dept,
      prixMin,
      prixMax,
      url,
      source: "eventim",
      classification: null,
      genre: null,
    };
  } catch (err) {
    logger.warn({ err }, "erreur parsing produit Eventim");
    return null;
  }
}

async function rechercherAttractionId(terme: string): Promise<string | null> {
  const params = new URLSearchParams({
    webId: WEB_ID,
    language: "fr",
    search_term: terme,
    top: "5",
  });

  const url = `${BASE_URL}/v1/attractions?${params.toString()}`;
  const res = await fetch(url, {
    headers: { Accept: "application/json", "User-Agent": "Mozilla/5.0" },
  });

  if (!res.ok) {
    logger.warn({ status: res.status, terme }, "Eventim attractions erreur");
    return null;
  }

  const data = (await res.json()) as Record<string, unknown>;
  const attractions = data["attractions"] as
    | Record<string, unknown>[]
    | undefined;
  if (!attractions?.length) return null;

  // Chercher d'abord une correspondance exacte (insensible à la casse)
  const termeLower = terme.toLowerCase().trim();
  const exact = attractions.find(
    (a) => (a["name"] as string)?.toLowerCase().trim() === termeLower,
  );
  const choisi = exact ?? attractions[0];
  const id = choisi?.["attractionId"] ?? choisi?.["id"];
  return id ? String(id) : null;
}

async function fetchProduitsParAttraction(
  attractionId: string,
): Promise<ConcertBrut[]> {
  const aujourd_hui = new Date().toISOString().split("T")[0]!;
  const dateFin = new Date();
  dateFin.setMonth(dateFin.getMonth() + 24);
  const dateFinStr = dateFin.toISOString().split("T")[0]!;

  const concerts: ConcertBrut[] = [];

  for (let page = 1; page <= MAX_PAGES; page++) {
    if (page > 1) await new Promise((r) => setTimeout(r, 200));

    const params = new URLSearchParams({
      webId: WEB_ID,
      language: "fr",
      attraction_ids: attractionId,
      date_from: aujourd_hui,
      date_to: dateFinStr,
      sort: "DateAsc",
      top: String(TAILLE_PAGE),
      page: String(page),
    });

    const url = `${BASE_URL}/v1/products?${params.toString()}`;
    const res = await fetch(url, {
      headers: { Accept: "application/json", "User-Agent": "Mozilla/5.0" },
    });

    if (!res.ok) {
      logger.error({ status: res.status }, "erreur HTTP Eventim products");
      break;
    }

    const data = (await res.json()) as Record<string, unknown>;
    const produits = data["products"] as Record<string, unknown>[] | undefined;
    if (!produits?.length) break;

    concerts.push(
      ...produits.map(mapProduit).filter((c): c is ConcertBrut => c !== null),
    );

    // Arrêter si on a récupéré tous les résultats
    const total = (data["totalResults"] as number | undefined) ?? 0;
    if (concerts.length >= total) break;
  }

  return concerts;
}

async function fetchProduitsParRegion(
  dept: string,
  classifications: string[],
): Promise<ConcertBrut[]> {
  const aujourd_hui = new Date().toISOString().split("T")[0]!;

  const params = new URLSearchParams({
    webId: WEB_ID,
    language: "fr",
    country: "FR",
    query: dept,
    date_from: aujourd_hui,
    sort: "DateAsc",
    top: String(TAILLE_PAGE),
  });

  for (const c of classifications) {
    params.append("categories", c);
  }

  const url = `${BASE_URL}/v1/products?${params.toString()}`;
  const res = await fetch(url, {
    headers: { Accept: "application/json", "User-Agent": "Mozilla/5.0" },
  });

  if (!res.ok) return [];

  const data = (await res.json()) as Record<string, unknown>;
  const produits = data["products"] as Record<string, unknown>[] | undefined;
  if (!produits) return [];

  return produits
    .map(mapProduit)
    .filter((c): c is ConcertBrut => c !== null)
    .filter((c) => c.dept === dept);
}

export class EventimSource implements SourceConcerts {
  async fetchArtiste(nom: string): Promise<ConcertBrut[]> {
    const attractionId = await rechercherAttractionId(nom);
    if (!attractionId) {
      logger.info({ artiste: nom }, "Eventim: artiste non trouvé");
      return [];
    }

    const concerts = await fetchProduitsParAttraction(attractionId);
    logger.info(
      { artiste: nom, total: concerts.length },
      "Eventim fetchArtiste",
    );
    return concerts;
  }

  async fetchRegion(
    depts: string[],
    classifs: string[] = ["music"],
  ): Promise<ConcertBrut[]> {
    const tous: ConcertBrut[] = [];

    for (const dept of depts) {
      const concerts = await fetchProduitsParRegion(dept, classifs);
      tous.push(...concerts);
      await new Promise((r) => setTimeout(r, 200));
    }

    return tous;
  }
}
