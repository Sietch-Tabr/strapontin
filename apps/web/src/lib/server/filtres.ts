import { db } from "$lib/server/db";
import { userConfig, concerts } from "@strapontin/core/schema";
import { eq, sql } from "drizzle-orm";
import type { SQL } from "drizzle-orm";

export async function lireFiltresStatut(
  url: URL,
  userId: string,
): Promise<{ inclureIndisponibles: boolean }> {
  const config = await db
    .select({ filtreDisponibles: userConfig.filtreDisponibles })
    .from(userConfig)
    .where(eq(userConfig.userId, userId))
    .then((rows) => rows[0] ?? null);

  const filtreDisponibles = config?.filtreDisponibles ?? true;

  const inclureIndisponibles = url.searchParams.has("indisponibles")
    ? url.searchParams.get("indisponibles") === "1"
    : !filtreDisponibles;

  return { inclureIndisponibles };
}

export function construireConditionStatut(
  inclureIndisponibles: boolean,
): SQL | null {
  if (inclureIndisponibles) return null;
  // rescheduled toujours visible (nouvelle date connue) — masquer offsale, cancelled, postponed
  return sql`(${concerts.statut} IS NULL OR ${concerts.statut} IN ('onsale', 'rescheduled'))`;
}
