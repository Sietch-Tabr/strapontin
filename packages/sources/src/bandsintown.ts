import pino from "pino";
import type { ConcertBrut, SourceConcerts } from "./types.js";

const logger = pino({ name: "source:bandsintown" });

const BASE_URL = "https://rest.bandsintown.com";

function extraireDept(ville: string | null): string | null {
  // Bandsintown ne donne pas de CP — on ne peut pas extraire le dept
  return null;
}

function parseDateBIT(dateStr: string): { date: string; heure: string | null } {
  // Format : "2025-06-15T20:00:00" ou "2025-06-15T00:00:00"
  const [datePart, heurePart] = dateStr.split("T");
  const heure = heurePart && heurePart !== "00:00:00" ? heurePart.slice(0, 5) : null;
  return { date: datePart ?? dateStr.slice(0, 10), heure };
}

function mapEvenement(evt: Record<string, unknown>, artiste: string): ConcertBrut | null {
  try {
    const dateStr = evt["datetime"] as string | undefined;
    if (!dateStr) return null;

    const { date, heure } = parseDateBIT(dateStr);

    const venue = evt["venue"] as Record<string, unknown> | undefined;
    const salle = (venue?.["name"] as string | undefined) ?? "Salle inconnue";
    const ville = (venue?.["city"] as string | undefined) ?? null;
    const pays = (venue?.["country"] as string | undefined) ?? "";

    // Filtrer : garder uniquement les événements en France
    if (pays !== "France" && pays !== "FR") return null;

    const url = (evt["url"] as string | undefined) ?? null;

    return {
      artiste,
      date,
      heure,
      salle,
      ville,
      dept: extraireDept(ville),
      prixMin: null,
      prixMax: null,
      url,
      source: "bandsintown",
    };
  } catch (err) {
    logger.warn({ err }, "erreur parsing événement Bandsintown");
    return null;
  }
}

export class BandsintownSource implements SourceConcerts {
  constructor(private readonly appId: string) {}

  async fetchArtiste(nom: string): Promise<ConcertBrut[]> {
    const nomEncode = encodeURIComponent(nom);
    const url = `${BASE_URL}/artists/${nomEncode}/events?app_id=${this.appId}&date=upcoming`;

    const res = await fetch(url);

    if (res.status === 404) {
      // Artiste inconnu de Bandsintown — normal
      return [];
    }

    if (!res.ok) {
      logger.error({ status: res.status, artiste: nom }, "erreur HTTP Bandsintown");
      return [];
    }

    const data = await res.json() as Record<string, unknown>[];

    if (!Array.isArray(data)) return [];

    const concerts = data
      .map((evt) => mapEvenement(evt as Record<string, unknown>, nom))
      .filter((c): c is ConcertBrut => c !== null);

    logger.info({ artiste: nom, total: concerts.length }, "BIT fetchArtiste");
    return concerts;
  }

  async fetchRegion(_depts: string[], _classifs: string[]): Promise<ConcertBrut[]> {
    // Bandsintown n'expose pas de recherche par région — non supporté
    return [];
  }
}
