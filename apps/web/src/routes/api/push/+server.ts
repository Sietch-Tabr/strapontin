import { db } from "$lib/server/db";
import { pushAbonnements } from "@strapontin/core/schema";
import { and, count, eq } from "drizzle-orm";
import { error, json } from "@sveltejs/kit";
import { lireConfig } from "$lib/server/config";
import { logAdmin } from "$lib/server/log-admin";
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
  if (
    endpoint.length > 2048 ||
    keys.p256dh.length > 256 ||
    keys.auth.length > 256
  ) {
    throw error(400, "Données d'abonnement invalides");
  }

  const [{ total }] = await db
    .select({ total: count() })
    .from(pushAbonnements)
    .where(eq(pushAbonnements.userId, locals.user.id));
  if (total >= 10) {
    throw error(429, "Trop d'abonnements push pour cet utilisateur");
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

  void logAdmin("info", "push", "abonnement push créé", { userId: locals.user.id });
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

  void logAdmin("info", "push", "abonnement push révoqué", { userId: locals.user.id });
  return json({ ok: true });
};
