/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.notification;

import fr.strapontin.core.UtilitairesChaine;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Corps HTML des emails liés au compte (relances avant purge, perte du
 * dernier canal de notification) — même gabarit visuel qu'{@link
 * EmailDigestTemplate}.
 * @since 3.0.0
 * @author Kwaite
 */
final class EmailCompteTemplate {

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

  private EmailCompteTemplate() {}

  /* package-private : appelé par EmailCompteService (même package). */
  static String nouvelleVersion(final String version, final String baseUrl) {
    final String sujet = "Strapontin a une nouvelle version";
    final String corps =
        "La version "
            + UtilitairesChaine.echapperHtml(version)
            + " de Strapontin est disponible. Découvrez ce qui a changé. Vous recevez ce message"
            + " parce que vous avez demandé à être prévenu des nouvelles versions, et qu'aucun"
            + " appareil n'est abonné aux notifications push : vous ne le recevrez qu'une fois"
            + " pour cette version, et pouvez changer ce choix dans vos paramètres.";
    return GABARIT.formatted(
        sujet, sujet, corps, baseUrl + "/changelog", "Voir les nouveautés", baseUrl);
  }

  /* package-private : appelé par EmailCompteService (même package). */
  static String avertissementCanalPerdu(final String baseUrl, final String cause) {
    final String sujet = "Vos notifications push Strapontin sont arrêtées";
    final String raison =
        cause != null && !cause.isBlank()
            ? "Le dernier appareil sur lequel vous receviez les notifications push a été retiré : "
                + UtilitairesChaine.echapperHtml(cause)
                + "."
            : "Le dernier appareil sur lequel vous receviez les notifications push ne les accepte"
                + " plus.";
    final String corps =
        raison
            + " Vos choix de notification push (artistes, salles) ont été"
            + " décochés, car ils ne peuvent plus servir sans appareil. Vos notifications par"
            + " email, si vous les avez activées, ne sont pas concernées. Pour retrouver le push,"
            + " ouvrez Strapontin sur votre appareil, autorisez les notifications dans le"
            + " navigateur, puis recochez dans vos paramètres les contenus souhaités.";
    return GABARIT.formatted(
        sujet, sujet, corps, baseUrl + "/parametres#notifications", "Mes notifications", baseUrl);
  }

  /* package-private : appelé par EmailCompteService (même package). */
  static String avertissementPushJamaisActive(final String baseUrl) {
    final String sujet = "Vos notifications Strapontin ne sont pas activées";
    final String corps =
        "Vous avez choisi d'être prévenu par notification push, mais aucun appareil n'a jamais"
            + " été enregistré (autorisation refusée par le navigateur ou activation"
            + " interrompue), et l'email est désactivé : de nouveaux concerts de vos artistes et"
            + " salles vous attendent sans vous avoir été signalés. Ouvrez Strapontin sur votre"
            + " appareil, autorisez les notifications puis réactivez-les dans vos paramètres, ou"
            + " activez l'email.";
    return GABARIT.formatted(
        sujet, sujet, corps, baseUrl + "/parametres#notifications", "Mes notifications", baseUrl);
  }

  /* package-private : appelé par EmailCompteService (même package). */
  static String relanceInactivite(final int joursGrace, final String baseUrl) {
    final String sujet = "Votre compte Strapontin va être supprimé pour inactivité";
    final String corps =
        ("Vous n'êtes pas connecté depuis plusieurs mois. Sans connexion dans les %d jours,"
                + " votre compte et vos préférences seront définitivement supprimés.")
            .formatted(joursGrace);
    return GABARIT.formatted(sujet, sujet, corps, baseUrl, "Me reconnecter", baseUrl);
  }

  /* package-private : appelé par EmailCompteService (même package). */
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
