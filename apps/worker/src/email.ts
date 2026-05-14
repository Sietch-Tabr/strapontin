import { Resend } from "resend";
import pino from "pino";
import { lireConfig } from "./config.js";

const logger = pino({ name: "email" });

type CacheResend = { resend: Resend; expireA: number };
let cacheResend: CacheResend | null = null;

async function obtenirResend(): Promise<Resend | null> {
  const maintenant = Date.now();
  if (cacheResend && maintenant < cacheResend.expireA)
    return cacheResend.resend;
  const cle = await lireConfig("RESEND_API_KEY").catch(() => null);
  if (!cle) return null;
  cacheResend = {
    resend: new Resend(cle),
    expireA: maintenant + 5 * 60 * 1000,
  };
  return cacheResend.resend;
}

const EXPEDITEUR = "Strapontin <noreply@strapontin.kwaite.fr>";
const REPLY_TO = "admin@strapontin.kwaite.fr";

function enTeteHtml(titre: string): string {
  return `<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${titre}</title>
</head>
<body style="font-family:system-ui,sans-serif;background:#f9f9f9;margin:0;padding:32px 16px">
<div style="max-width:520px;margin:0 auto;background:#fff;border-radius:8px;padding:32px;border:1px solid #e5e5e5">`;
}

function piedHtml(baseUrl: string): string {
  return `</div>
<p style="text-align:center;color:#aaa;font-size:12px;margin-top:24px">
  Strapontin — <a href="${baseUrl}/parametres" style="color:#aaa">Gérer mes préférences</a>
</p>
</body>
</html>`;
}

async function obtenirBaseUrl(): Promise<string> {
  return (
    (await lireConfig("PUBLIC_BASE_URL").catch(() => null)) ??
    process.env["PUBLIC_BASE_URL"] ??
    "http://localhost:3000"
  );
}

export type ConcertDigest = {
  artiste: string;
  date: string;
  salle: string;
  ville: string | null;
  url: string | null;
};

function formaterDate(dateIso: string): string {
  const d = new Date(dateIso + "T00:00:00");
  return d.toLocaleDateString("fr-FR", {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
  });
}

function lignesConcerts(concerts: ConcertDigest[]): string {
  return concerts
    .map((c) => {
      const lieu = [c.salle, c.ville].filter(Boolean).join(" · ");
      const lien = c.url
        ? ` — <a href="${c.url}" style="color:#111;font-size:13px">Billets →</a>`
        : "";
      return `<tr>
  <td style="padding:10px 0;border-bottom:1px solid #f0f0f0;vertical-align:top">
    <div style="font-weight:600;color:#111;font-size:14px">${c.artiste}</div>
    <div style="color:#666;font-size:13px;margin-top:2px">${formaterDate(c.date)}${lien}</div>
    <div style="color:#999;font-size:12px;margin-top:2px">${lieu}</div>
  </td>
</tr>`;
    })
    .join("\n");
}

export async function envoyerDigestConcerts(params: {
  email: string;
  concerts: ConcertDigest[];
}): Promise<boolean> {
  const resend = await obtenirResend();
  if (!resend) {
    logger.warn("RESEND_API_KEY absent — digest concerts non envoyé");
    return false;
  }

  if (params.concerts.length === 0) return false;

  const nb = params.concerts.length;
  const sujet =
    nb === 1
      ? "1 nouveau concert correspond à vos préférences"
      : `${nb} nouveaux concerts correspondent à vos préférences`;

  const baseUrl = await obtenirBaseUrl();
  const corps = `${enTeteHtml(sujet)}
<h2 style="font-size:20px;font-weight:600;color:#111;margin:0 0 8px">${sujet}</h2>
<p style="color:#555;font-size:14px;margin:0 0 24px">Voici les nouveaux concerts ajoutés :</p>
<table style="border-collapse:collapse;width:100%">
${lignesConcerts(params.concerts)}
</table>
<p style="margin-top:24px">
  <a href="${baseUrl}/spectacles"
     style="display:inline-block;background:#111;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-size:14px;font-weight:500">
    Voir mes spectacles →
  </a>
</p>
${piedHtml(baseUrl)}`;

  const { error } = await resend.emails.send({
    from: EXPEDITEUR,
    replyTo: REPLY_TO,
    to: params.email,
    subject: sujet,
    html: corps,
  });

  if (error) {
    logger.error({ err: error, email: params.email }, "échec envoi digest");
    return false;
  }
  logger.info({ email: params.email, nb }, "digest envoyé");
  return true;
}

export async function envoyerAlerteWorkerInactif(params: {
  email: string;
  derniereActiviteIso: string | null;
}): Promise<void> {
  const resend = await obtenirResend();
  if (!resend) {
    logger.warn("RESEND_API_KEY absent — alerte worker non envoyée");
    return;
  }

  const sujet = "Strapontin — worker inactif";
  const baseUrl = await obtenirBaseUrl();
  const detail = params.derniereActiviteIso
    ? `Dernier job complété : ${new Date(params.derniereActiviteIso).toLocaleString("fr-FR")}.`
    : "Aucun job complété enregistré.";

  const corps = `${enTeteHtml(sujet)}
<h2 style="font-size:20px;font-weight:600;color:#c00;margin:0 0 8px">Worker inactif</h2>
<p style="color:#555;font-size:14px;margin:0 0 16px">${detail}</p>
<p style="color:#555;font-size:14px;margin:0 0 24px">Les fetches Ticketmaster et les notifications n'ont pas été traités depuis plus de 3 heures.</p>
<p style="margin-top:24px">
  <a href="${baseUrl}/admin"
     style="display:inline-block;background:#111;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-size:14px;font-weight:500">
    Aller à l'administration →
  </a>
</p>
${piedHtml(baseUrl)}`;

  const { error } = await resend.emails.send({
    from: EXPEDITEUR,
    replyTo: REPLY_TO,
    to: params.email,
    subject: sujet,
    html: corps,
    text: `Worker inactif — ${detail} Consultez l'administration : ${baseUrl}/admin`,
  });

  if (error) {
    logger.error({ err: error }, "échec envoi alerte worker");
  } else {
    logger.info({ email: params.email }, "alerte worker envoyée");
  }
}
