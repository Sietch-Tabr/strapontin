// Client Resend pour les emails transactionnels côté web.
// Si RESEND_API_KEY est absent, toutes les fonctions retournent silencieusement sans erreur.

import { Resend } from "resend";
import { RESEND_API_KEY, CONTACT_EMAIL } from "$env/static/private";
import { PUBLIC_BASE_URL } from "$env/static/public";
import pino from "pino";

const logger = pino({ name: "email" });

function echapperHtml(texte: string): string {
  return texte
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#x27;");
}

const resend = RESEND_API_KEY ? new Resend(RESEND_API_KEY) : null;

export const resendDisponible = !!RESEND_API_KEY;

const EXPEDITEUR = "Strapontin <noreply@strapontin.kwaite.fr>";
const REPLY_TO = CONTACT_EMAIL || "admin@strapontin.kwaite.fr";

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

function piedHtml(): string {
  return `</div>
<p style="text-align:center;color:#aaa;font-size:12px;margin-top:24px">
  Strapontin — <a href="${PUBLIC_BASE_URL}/parametres" style="color:#aaa">Gérer mes préférences</a>
</p>
</body>
</html>`;
}

export async function envoyerInvitation(params: {
  email: string;
  identifiant: string;
  motDePasse: string;
}): Promise<void> {
  if (!resend) {
    logger.warn("RESEND_API_KEY absent — email invitation non envoyé");
    return;
  }

  const corps = `${enTeteHtml("Votre invitation Strapontin")}
<h2 style="font-size:20px;font-weight:600;color:#111;margin:0 0 8px">Bienvenue sur Strapontin</h2>
<p style="color:#555;margin:0 0 24px">Votre compte a été créé. Voici vos identifiants de connexion :</p>
<table style="border-collapse:collapse;width:100%;margin-bottom:24px">
  <tr>
    <td style="padding:8px 0;color:#888;font-size:14px;width:130px">Identifiant</td>
    <td style="padding:8px 0;font-family:monospace;font-size:14px;color:#111">${params.identifiant}</td>
  </tr>
  <tr>
    <td style="padding:8px 0;color:#888;font-size:14px">Mot de passe</td>
    <td style="padding:8px 0;font-family:monospace;font-size:14px;color:#111">${params.motDePasse}</td>
  </tr>
</table>
<p style="color:#555;font-size:14px;margin:0 0 24px">
  Il vous sera demandé de choisir un nouvel identifiant et un nouveau mot de passe à la première connexion.
</p>
<a href="${PUBLIC_BASE_URL}/login"
   style="display:inline-block;background:#111;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-size:14px;font-weight:500">
  Se connecter →
</a>
${piedHtml()}`;

  const { error } = await resend.emails.send({
    from: EXPEDITEUR,
    replyTo: REPLY_TO,
    to: params.email,
    subject: "Votre invitation Strapontin",
    html: corps,
  });

  if (error) {
    logger.error({ err: error, email: params.email }, "échec envoi invitation");
  } else {
    logger.info({ email: params.email }, "invitation envoyée");
  }
}

export async function envoyerResetMotDePasse(params: {
  email: string;
  lienReset: string;
}): Promise<void> {
  if (!resend) {
    logger.warn("RESEND_API_KEY absent — email reset non envoyé");
    return;
  }

  const corps = `${enTeteHtml("Réinitialisation de votre mot de passe")}
<h2 style="font-size:20px;font-weight:600;color:#111;margin:0 0 8px">Réinitialisation du mot de passe</h2>
<p style="color:#555;margin:0 0 24px">
  Vous avez demandé la réinitialisation de votre mot de passe. Cliquez sur le bouton ci-dessous.
  Ce lien est valable 1 heure.
</p>
<a href="${params.lienReset}"
   style="display:inline-block;background:#111;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-size:14px;font-weight:500">
  Réinitialiser mon mot de passe →
</a>
<p style="color:#aaa;font-size:13px;margin-top:24px">
  Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.
</p>
${piedHtml()}`;

  const { error } = await resend.emails.send({
    from: EXPEDITEUR,
    replyTo: REPLY_TO,
    to: params.email,
    subject: "Réinitialisation de votre mot de passe Strapontin",
    html: corps,
  });

  if (error) {
    logger.error({ err: error, email: params.email }, "échec envoi reset MDP");
  } else {
    logger.info({ email: params.email }, "email reset MDP envoyé");
  }
}

export async function envoyerSignalement(params: {
  type: string;
  message: string;
  expediteurEmail: string;
  expediteurNom: string;
}): Promise<void> {
  if (!resend) {
    logger.warn("RESEND_API_KEY absent — signalement non envoyé");
    return;
  }

  const types: Record<string, string> = {
    bug: "Bug",
    suggestion: "Amélioration",
    question: "Question",
  };
  const typeLabel = types[params.type] ?? params.type;

  const corps = `${enTeteHtml(`Signalement — ${typeLabel}`)}
<h2 style="font-size:20px;font-weight:600;color:#111;margin:0 0 8px">Signalement — ${typeLabel}</h2>
<table style="border-collapse:collapse;width:100%;margin-bottom:24px">
  <tr>
    <td style="padding:8px 0;color:#888;font-size:14px;width:130px">Utilisateur</td>
    <td style="padding:8px 0;font-size:14px;color:#111">${echapperHtml(params.expediteurNom)}</td>
  </tr>
  <tr>
    <td style="padding:8px 0;color:#888;font-size:14px">Email</td>
    <td style="padding:8px 0;font-size:14px;color:#111">${echapperHtml(params.expediteurEmail)}</td>
  </tr>
</table>
<p style="color:#555;font-size:14px;white-space:pre-wrap;border-left:3px solid #e5e5e5;padding-left:12px;margin:0">${echapperHtml(params.message)}</p>
${piedHtml()}`;

  const { error } = await resend.emails.send({
    from: EXPEDITEUR,
    replyTo: params.expediteurEmail,
    to: REPLY_TO,
    subject: `[Strapontin] Signalement — ${typeLabel} — ${params.expediteurNom}`,
    html: corps,
  });

  if (error) {
    logger.error({ err: error }, "échec envoi signalement");
  } else {
    logger.info({ expediteur: params.expediteurEmail }, "signalement envoyé");
  }
}
