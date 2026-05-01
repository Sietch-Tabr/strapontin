import { db } from "$lib/server/db";
import { userConfig, concerts } from "@strapontin/core/schema";
import { eq, or, sql } from "drizzle-orm";
import type { SQL } from "drizzle-orm";

export async function lireFiltresStatut(
  url: URL,
  userId: string,
): Promise<{ inclureComplets: boolean; inclureAnnules: boolean }> {
  const config = await db
    .select({ filtreDisponibles: userConfig.filtreDisponibles })
    .from(userConfig)
    .where(eq(userConfig.userId, userId))
    .then((rows) => rows[0] ?? null);

  const filtreDisponibles = config?.filtreDisponibles ?? true;

  const inclureComplets = url.searchParams.has("complets")
    ? url.searchParams.get("complets") === "1"
    : !filtreDisponibles;
  const inclureAnnules = url.searchParams.has("annules")
    ? url.searchParams.get("annules") === "1"
    : !filtreDisponibles;

  return { inclureComplets, inclureAnnules };
}

export function construireConditionStatut(
  inclureComplets: boolean,
  inclureAnnules: boolean,
): SQL | null {
  if (inclureComplets && inclureAnnules) return null;
  const statuts: SQL[] = [
    sql`(${concerts.statut} IS NULL OR ${concerts.statut} = 'onsale')`,
  ];
  if (inclureComplets) statuts.push(sql`${concerts.statut} = 'offsale'`);
  if (inclureAnnules)
    statuts.push(
      sql`${concerts.statut} IN ('cancelled', 'postponed', 'rescheduled')`,
    );
  return or(...statuts)!;
}
