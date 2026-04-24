// Envoi de notifications Web Push aux abonnés.

import pino from "pino";
import webPush from "web-push";
import { eq } from "drizzle-orm";
import { pushAbonnements } from "@strapontin/core/schema";
import { db } from "./db.js";
import { lireConfig } from "./config.js";

const logger = pino({ name: "push" });

type CacheVapid = { publicKey: string; privateKey: string; expireA: number };
let cacheVapid: CacheVapid | null = null;

async function obtenirClesVapid(): Promise<{
  publicKey: string;
  privateKey: string;
} | null> {
  const maintenant = Date.now();
  if (cacheVapid && maintenant < cacheVapid.expireA) {
    return {
      publicKey: cacheVapid.publicKey,
      privateKey: cacheVapid.privateKey,
    };
  }

  const [publicKey, privateKey] = await Promise.all([
    lireConfig("VAPID_PUBLIC_KEY").catch(() => null),
    lireConfig("VAPID_PRIVATE_KEY").catch(() => null),
  ]);

  if (!publicKey || !privateKey) return null;

  cacheVapid = {
    publicKey,
    privateKey,
    expireA: maintenant + 5 * 60 * 1000,
  };
  return { publicKey: cacheVapid.publicKey, privateKey: cacheVapid.privateKey };
}

export interface ConcertPush {
  artiste: string;
  date: string;
  salle: string;
  ville: string | null;
  url: string | null;
}

function construirePayload(concerts: ConcertPush[], baseUrl: string): string {
  const premiere = concerts[0]!;
  const titre =
    concerts.length === 1
      ? `${premiere.artiste} — ${premiere.salle}`
      : `${concerts.length} nouveaux concerts`;
  const corps =
    concerts.length === 1
      ? `${premiere.date}${premiere.ville ? ` · ${premiere.ville}` : ""}`
      : concerts.map((c) => `${c.artiste} · ${c.date}`).join("\n");

  return JSON.stringify({
    title: titre,
    body: corps,
    icon: `${baseUrl}/icon-192.png`,
    url: `${baseUrl}/spectacles`,
  });
}

export async function envoyerPushDigest(
  userId: string,
  concerts: ConcertPush[],
): Promise<void> {
  if (concerts.length === 0) return;

  const cles = await obtenirClesVapid();
  if (!cles) {
    logger.warn({ userId }, "VAPID non configuré — push ignoré");
    return;
  }

  const abonnements = await db
    .select({
      id: pushAbonnements.id,
      endpoint: pushAbonnements.endpoint,
      p256dh: pushAbonnements.p256dh,
      auth: pushAbonnements.auth,
    })
    .from(pushAbonnements)
    .where(eq(pushAbonnements.userId, userId));

  if (abonnements.length === 0) return;

  const baseUrl =
    (await lireConfig("PUBLIC_BASE_URL").catch(() => null)) ??
    process.env["PUBLIC_BASE_URL"] ??
    "https://strapontin.kwaite.fr";

  webPush.setVapidDetails(
    `mailto:${process.env["CONTACT_EMAIL"] ?? "admin@example.com"}`,
    cles.publicKey,
    cles.privateKey,
  );

  const payload = construirePayload(concerts, baseUrl);

  await Promise.allSettled(
    abonnements.map(async (ab) => {
      try {
        await webPush.sendNotification(
          { endpoint: ab.endpoint, keys: { p256dh: ab.p256dh, auth: ab.auth } },
          payload,
        );
      } catch (err: unknown) {
        const status = (err as { statusCode?: number }).statusCode;
        if (status === 404 || status === 410) {
          // Abonnement expiré ou révoqué — supprimer
          await db
            .delete(pushAbonnements)
            .where(eq(pushAbonnements.id, ab.id))
            .catch(() => null);
          logger.info(
            { userId, endpoint: ab.endpoint },
            "abonnement push révoqué, supprimé",
          );
        } else {
          logger.warn(
            { err, userId, endpoint: ab.endpoint },
            "push: échec envoi",
          );
        }
      }
    }),
  );
}
