/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Corps HTML des emails de relance avant purge de compte (inactivité,
 * invitation jamais activée) — même gabarit visuel qu'{@link
 * EmailDigestTemplate}.
 * @since 3.0.0
 * @author Kwaite
 */
final class PurgeCompteEmailTemplate {

  private static final DateTimeFormatter DATE_LIMITE =
      DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);

  private static final String GABARIT =
      """
      <!DOCTYPE html>
      <html lang="fr">
      <head><meta charset="utf-8"><title>%s</title></head>
      <body style="font-family:system-ui,sans-serif;background:#f9f9f9;margin:0;padding:32px 16px">
      <div style="max-width:520px;margin:0 auto;background:#fff;border-radius:8px;padding:32px;border:1px solid #e5e5e5">
      <h2 style="font-size:20px;font-weight:600;color:#111;margin:0 0 8px">%s</h2>
      <p style="color:#555;font-size:14px;margin:0 0 24px">%s</p>
      <p style="margin-top:24px">
        <a href="%s"
           style="display:inline-block;background:#111;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-size:14px;font-weight:500">
          %s →
        </a>
      </p>
      </div>
      <p style="text-align:center;color:#aaa;font-size:12px;margin-top:24px">
        Strapontin — <a href="%s/aide" style="color:#aaa">Vos données</a>
      </p>
      </body>
      </html>
      """;

  private PurgeCompteEmailTemplate() {}

  /* package-private : appelé par PurgeCompteEmailService (même package). */
  static String relanceInactivite(final int joursGrace, final String baseUrl) {
    final String sujet = "Votre compte Strapontin va être supprimé pour inactivité";
    final String corps =
        "Vous n'êtes pas connecté depuis plusieurs mois. Sans connexion dans les %d jours,"
            + " votre compte et vos préférences seront définitivement supprimés."
                .formatted(joursGrace);
    return GABARIT.formatted(sujet, sujet, corps, baseUrl, "Me reconnecter", baseUrl);
  }

  /* package-private : appelé par PurgeCompteEmailService (même package). */
  static String relanceInvitation(
      final String lienActivation, final LocalDate dateLimite, final String baseUrl) {
    final String sujet = "Votre invitation Strapontin expire bientôt";
    final String corps =
        "Vous avez été invité sur Strapontin mais votre compte n'a jamais été activé."
            + " Ce nouveau lien est valable jusqu'au %s inclus ; passé cette date,"
                .formatted(DATE_LIMITE.format(dateLimite))
            + " l'invitation sera annulée.";
    return GABARIT.formatted(sujet, sujet, corps, lienActivation, "Activer mon compte", baseUrl);
  }
}
