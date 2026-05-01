import { db } from "$lib/server/db";
import { userArtistes, userSalles, userConfig } from "@strapontin/core/schema";
import { eq } from "drizzle-orm";
import { error } from "@sveltejs/kit";
import type { RequestHandler } from "./$types";

export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.user) throw error(401);

  const [artistes, salles, configs] = await Promise.all([
    db
      .select({
        nom: userArtistes.artisteAffiche,
        normalise: userArtistes.artisteNormalise,
      })
      .from(userArtistes)
      .where(eq(userArtistes.userId, locals.user.id))
      .orderBy(userArtistes.artisteNormalise),
    db
      .select({
        salleId: userSalles.salleId,
        tmVenueId: userSalles.tmVenueId,
      })
      .from(userSalles)
      .where(eq(userSalles.userId, locals.user.id))
      .orderBy(userSalles.salleId),
    db
      .select({
        departements: userConfig.departements,
        filtreDisponibles: userConfig.filtreDisponibles,
        frequenceNotifications: userConfig.frequenceNotifications,
        notifArtistes: userConfig.notifArtistes,
        notifSalles: userConfig.notifSalles,
        theme: userConfig.theme,
      })
      .from(userConfig)
      .where(eq(userConfig.userId, locals.user.id)),
  ]);

  const config = configs[0] ?? null;

  const exportJson = JSON.stringify(
    {
      version: 1,
      exporteAt: new Date().toISOString(),
      artistes,
      salles,
      config,
    },
    null,
    2,
  );

  const nomFichier = `strapontin-preferences-${new Date().toISOString().split("T")[0]}.json`;

  return new Response(exportJson, {
    headers: {
      "Content-Type": "application/json",
      "Content-Disposition": `attachment; filename="${nomFichier}"`,
    },
  });
};
