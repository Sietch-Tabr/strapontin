import pino from "pino";
import type { ConcertBrut, SourceConcerts } from "./types.js";

const logger = pino({ name: "source:eventim" });

const BASE_URL =
  "https://public-api.eventim.com/websearch/search/api/exploration";
const TAILLE_PAGE = 20;

// Classifications Eventim France
const CLASSIFICATIONS_MUSIQUE = ["music"];
const CLASSIFICATIONS_SCENE = ["theatre", "dance", "opera", "musical"];

function extraireDept(codePostal: string | null | undefined): string | null {
  if (!codePostal) return null;
  if (codePostal.startsWith("97")) return codePostal.slice(0, 3);
  return codePostal.slice(0, 2);
}

function parseDateEventim(dateStr: string): {
  date: string;
  heure: string | null;
} {
  // Format : "2025-06-15T20:00:00+02:00" ou "2025-06-15"
  const datePart = dateStr.slice(0, 10);
  const tIndex = dateStr.indexOf("T");
  if (tIndex === -1) return { date: datePart, heure: null };
  const heurePart = dateStr.slice(tIndex + 1, tIndex + 6);
  const heure = heurePart !== "00:00" ? heurePart : null;
  return { date: datePart, heure };
}

function mapProduit(produit: Record<string, unknown>): ConcertBrut | null {
  try {
    const dateStr = (produit["startDate"] ?? produit["start_date"]) as
      | string
      | undefined;
    if (!dateStr) return null;

    const { date, heure } = parseDateEventim(dateStr);

    const attraction = produit["attraction"] as
      | Record<string, unknown>
      | undefined;
    const artiste = (attraction?.["name"] ?? produit["name"]) as
      | string
      | undefined;
    if (!artiste) return null;

    const venue = produit["venue"] as Record<string, unknown> | undefined;
    const salle = (venue?.["name"] as string | undefined) ?? "Salle inconnue";
    const ville = (venue?.["city"] as string | undefined) ?? null;
    const cp = (venue?.["postalCode"] ?? venue?.["postal_code"]) as
      | string
      | undefined;
    const dept = extraireDept(cp);

    const priceFrom = produit["priceFrom"] as number | null | undefined;
    const priceTo = produit["priceTo"] as number | null | undefined;

    const productUrl = produit["url"] as string | undefined;
    const url = productUrl
      ? productUrl.startsWith("http")
        ? productUrl
        : `https://www.eventim.fr${productUrl}`
      : null;

    return {
      artiste: artiste as string,
      date,
      heure,
      salle,
      ville,
      dept,
      prixMin: priceFrom ?? null,
      prixMax: priceTo ?? null,
      url,
      source: "eventim",
    };
  } catch (err) {
    logger.warn({ err }, "erreur parsing produit Eventim");
    return null;
  }
}

async function rechercherAttraction(terme: string): Promise<string | null> {
  const url = `${BASE_URL}/v1/attractions?search_term=${encodeURIComponent(terme)}&language=fr&country=FR&size=1`;
  const res = await fetch(url, {
    headers: { Accept: "application/json", "User-Agent": "Mozilla/5.0" },
  });

  if (!res.ok) return null;

  const data = (await res.json()) as Record<string, unknown>;
  const attractions = data["attractions"] as
    | Record<string, unknown>[]
    | undefined;
  const id = attractions?.[0]?.["attractionId"] ?? attractions?.[0]?.["id"];
  return id ? String(id) : null;
}

async function fetchProduitsParAttraction(
  attractionId: string,
): Promise<ConcertBrut[]> {
  const params = new URLSearchParams({
    attraction_ids: attractionId,
    language: "fr",
    country: "FR",
    size: String(TAILLE_PAGE),
    sort: "DateAsc",
  });

  const url = `${BASE_URL}/v1/products?${params.toString()}`;
  const res = await fetch(url, {
    headers: { Accept: "application/json", "User-Agent": "Mozilla/5.0" },
  });

  if (!res.ok) {
    logger.error({ status: res.status }, "erreur HTTP Eventim products");
    return [];
  }

  const data = (await res.json()) as Record<string, unknown>;
  const produits = data["products"] as Record<string, unknown>[] | undefined;
  if (!produits) return [];

  return produits.map(mapProduit).filter((c): c is ConcertBrut => c !== null);
}

async function fetchProduitsParRegion(
  dept: string,
  classifications: string[],
): Promise<ConcertBrut[]> {
  const params = new URLSearchParams({
    language: "fr",
    country: "FR",
    size: String(TAILLE_PAGE),
    sort: "DateAsc",
    query: dept,
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
    const attractionId = await rechercherAttraction(nom);
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
    classifs: string[] = CLASSIFICATIONS_MUSIQUE,
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
