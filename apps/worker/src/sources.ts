import { TicketmasterSource, EventimSource } from "@strapontin/sources";
import { lireConfig } from "./config.js";

type CacheConfig = { valeur: string; expireA: number };
const cacheTmKey: { actuel: CacheConfig | null } = { actuel: null };

async function lireCleTicketmaster(): Promise<string> {
  const maintenant = Date.now();
  if (cacheTmKey.actuel && maintenant < cacheTmKey.actuel.expireA) {
    return cacheTmKey.actuel.valeur;
  }
  const valeurDb = await lireConfig("TICKETMASTER_API_KEY").catch(() => null);
  const valeur = valeurDb ?? process.env["TICKETMASTER_API_KEY"] ?? "";
  cacheTmKey.actuel = { valeur, expireA: maintenant + 5 * 60 * 1000 };
  return valeur;
}

export async function obtenirTicketmaster(): Promise<TicketmasterSource> {
  const cle = await lireCleTicketmaster();
  return new TicketmasterSource(cle);
}

export const eventim = new EventimSource();
