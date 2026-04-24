import { db } from "$lib/server/db";
import { pushAbonnements } from "@strapontin/core/schema";
import { and, eq } from "drizzle-orm";
import { error, json } from "@sveltejs/kit";
import { lireConfig } from "$lib/server/config";
import type { RequestHandler } from "./$types";

export const GET: RequestHandler = async () => {
  const clePublique =
    (await lireConfig("VAPID_PUBLIC_KEY").catch(() => null)) ??
    process.env["VAPID_PUBLIC_KEY"] ??
    null;

  if (!clePublique) {
    return json({ disponible: false, clePublique: null });
  }

  return json({ disponible: true, clePublique });
};

export const POST: RequestHandler = async ({ request, locals }) => {
  if (!locals.user) throw error(401);

  let corps: { endpoint: string; keys: { p256dh: string; auth: string } };
  try {
    corps = (await request.json()) as typeof corps;
  } catch {
    throw error(400, "Corps JSON invalide");
  }

  const { endpoint, keys } = corps;
  if (!endpoint || !keys?.p256dh || !keys?.auth) {
    throw error(400, "Abonnement incomplet");
  }

  await db
    .insert(pushAbonnements)
    .values({
      userId: locals.user.id,
      endpoint,
      p256dh: keys.p256dh,
      auth: keys.auth,
    })
    .onConflictDoNothing();

  return json({ ok: true }, { status: 201 });
};

export const DELETE: RequestHandler = async ({ request, locals }) => {
  if (!locals.user) throw error(401);

  let corps: { endpoint: string };
  try {
    corps = (await request.json()) as typeof corps;
  } catch {
    throw error(400, "Corps JSON invalide");
  }

  if (!corps.endpoint) throw error(400, "Endpoint manquant");

  await db
    .delete(pushAbonnements)
    .where(
      and(
        eq(pushAbonnements.userId, locals.user.id),
        eq(pushAbonnements.endpoint, corps.endpoint),
      ),
    );

  return json({ ok: true });
};
