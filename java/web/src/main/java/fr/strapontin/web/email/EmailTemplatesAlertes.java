/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import fr.strapontin.web.admin.LogAdminService.EntreeLogAdmin;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Génération des corps HTML des emails d'alerte technique (backup, intégrité, bounce/plainte spam,
 * worker inactif, quota Resend, digest de logs admin).
 *
 * <p>Extrait d'{@link EmailTemplates} (dont elle réutilise les helpers {@code enveloppErreur}/
 * {@code bouton}/{@code e}) pour rester sous la limite de 500 lignes par fichier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class EmailTemplatesAlertes {

  private static final String CHEMIN_ADMIN = "/admin";
  private static final String LIBELLE_ALLER_ADMINISTRATION = "Aller à l'administration →";

  private EmailTemplatesAlertes() {}

  /**
   * Alerte d'échec du backup nocturne.
   *
   * @param horodatage l'horodatage YYYYMMDD_HHMMSS, ou chaîne vide si inconnu
   * @param raison la commande ayant échoué, ou chaîne vide
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'alerte backup
   */
  static String alerteBackup(final String horodatage, final String raison, final String baseUrl) {
    final String horodatageHtml =
        (horodatage != null && !horodatage.isBlank())
            ? "Démarré le : " + EmailTemplates.e(horodatage)
            : "Horodatage indisponible.";
    final String raisonHtml =
        (raison != null && !raison.isBlank())
            ? ("""
            <p style="color: #333; font-size: 13px; font-family: monospace; background: #f5f5f5; \
            border-left: 3px solid %s; padding: 8px 12px; margin: 0 0 16px; word-break: break-all">%s</p>
            """
                .formatted(EmailTemplates.C_ERREUR, EmailTemplates.e(raison)))
            : "";
    final String corps =
        """
        <p style="color: %s; font-size: 14px; margin: 0 0 8px">%s</p>
        %s
        <p style="color: %s; font-size: 14px; margin: 0 0 24px">
          Le backup local nocturne (dump PostgreSQL + .env + logs) n'a pas pu être
          complété. Vérifiez l'espace disque et les logs systemd sur le serveur.
        </p>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(
                EmailTemplates.C_TEXTE,
                horodatageHtml,
                raisonHtml,
                EmailTemplates.C_TEXTE,
                EmailTemplates.bouton(baseUrl + CHEMIN_ADMIN, LIBELLE_ALLER_ADMINISTRATION));
    return EmailTemplates.enveloppErreur("Échec du backup nocturne", corps, baseUrl);
  }

  /**
   * Alerte de contrôle d'intégrité (AIDE) — modification détectée hors déploiement légitime sur un
   * LXC prod/dev.
   *
   * @param detail sortie résumée d'{@code aide --check} (fichiers modifiés/ajoutés/supprimés)
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'alerte
   */
  static String alerteIntegrite(final String detail, final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; font-size: 14px; margin: 0 0 16px; font-family: monospace;\
        white-space: pre-wrap; background: #f5f5f5; border-left: 3px solid %s; padding: 8px 12px">\
        %s</p>
        <p style="color: %s; font-size: 14px; margin: 0 0 24px">
          Une modification de fichier a été détectée hors d'un déploiement légitime
          (JARs, .env, scripts/, units systemd). Vérifiez l'origine sur le serveur.
        </p>
        """
            .formatted(
                EmailTemplates.C_ERREUR,
                EmailTemplates.C_ERREUR,
                EmailTemplates.e(detail),
                EmailTemplates.C_TEXTE);
    return EmailTemplates.enveloppErreur("Alerte contrôle d'intégrité", corps, baseUrl);
  }

  /**
   * Alerte de bounce email (rejet définitif par le serveur destinataire).
   *
   * @param emailBouncee l'adresse email ayant produit le bounce
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'alerte bounce
   */
  static String alerteBounce(final String emailBouncee, final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; font-size: 14px; margin: 0 0 16px">
          Un email envoyé par Strapontin a été rejeté définitivement par le serveur destinataire.
        </p>
        <p style="color: %s; font-size: 14px; margin: 0 0 24px">
          Adresse concernée : <strong>%s</strong>
        </p>
        <p style="color: %s; font-size: 14px; margin: 0 0 24px">
          Vérifiez l'adresse email de cet utilisateur depuis la page d'administration.
        </p>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(
                EmailTemplates.C_TEXTE,
                EmailTemplates.C_TEXTE,
                EmailTemplates.e(emailBouncee),
                EmailTemplates.C_TEXTE,
                EmailTemplates.bouton(baseUrl + CHEMIN_ADMIN, LIBELLE_ALLER_ADMINISTRATION));
    return EmailTemplates.enveloppErreur("Bounce email", corps, baseUrl);
  }

  /**
   * Alerte de plainte spam (destinataire ayant signalé un email Strapontin comme indésirable).
   *
   * @param emailPlaignant l'adresse email à l'origine de la plainte
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'alerte plainte spam
   */
  static String alertePlainteSpam(final String emailPlaignant, final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; font-size: 14px; margin: 0 0 16px">
          Un destinataire a signalé un email envoyé par Strapontin comme indésirable (spam).
        </p>
        <p style="color: %s; font-size: 14px; margin: 0 0 24px">
          Adresse concernée : <strong>%s</strong>
        </p>
        <p style="color: %s; font-size: 14px; margin: 0 0 24px">
          Une plainte spam menace la réputation d'envoi du domaine. Vérifiez l'adresse email de
          cet utilisateur depuis la page d'administration.
        </p>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(
                EmailTemplates.C_TEXTE,
                EmailTemplates.C_TEXTE,
                EmailTemplates.e(emailPlaignant),
                EmailTemplates.C_TEXTE,
                EmailTemplates.bouton(baseUrl + CHEMIN_ADMIN, LIBELLE_ALLER_ADMINISTRATION));
    return EmailTemplates.enveloppErreur("Plainte spam email", corps, baseUrl);
  }

  /**
   * Alerte de worker inactif (aucun job depuis plus de 3 heures).
   *
   * @param detail description de la dernière activité (jamais null)
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'alerte worker
   */
  static String alerteWorker(final String detail, final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; font-size: 14px; margin: 0 0 16px">%s</p>
        <p style="color: %s; font-size: 14px; margin: 0 0 24px">
          Les fetches Ticketmaster et les notifications n'ont pas été traités depuis plus de 3 heures.
        </p>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(
                EmailTemplates.C_TEXTE,
                EmailTemplates.e(detail),
                EmailTemplates.C_TEXTE,
                EmailTemplates.bouton(baseUrl + CHEMIN_ADMIN, LIBELLE_ALLER_ADMINISTRATION));
    return EmailTemplates.enveloppErreur("Worker inactif", corps, baseUrl);
  }

  /**
   * Alerte de seuil (80%) sur le quota d'envoi Resend jour et/ou mois (suite de l'incident).
   *
   * @param envoyesJour nombre d'envois comptabilisés pour le jour courant
   * @param limiteJour limite journalière Resend (tier gratuit : 100)
   * @param envoyesMois nombre d'envois comptabilisés pour le mois courant
   * @param limiteMois limite mensuelle Resend (tier gratuit : 3000)
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'alerte quota Resend
   */
  static String alerteQuotaResend(
      final int envoyesJour,
      final int limiteJour,
      final int envoyesMois,
      final int limiteMois,
      final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; font-size: 14px; margin: 0 0 8px">
          Le quota d'envoi Resend approche de sa limite.
        </p>
        <p style="color: %s; font-size: 14px; margin: 0 0 24px">
          Jour : %d / %d envois. Mois : %d / %d envois. Au-delà, les emails
          transactionnels et notifications sont bloqués avant tout appel réseau.
        </p>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(
                EmailTemplates.C_TEXTE,
                EmailTemplates.C_TEXTE,
                envoyesJour,
                limiteJour,
                envoyesMois,
                limiteMois,
                EmailTemplates.bouton(baseUrl + CHEMIN_ADMIN, LIBELLE_ALLER_ADMINISTRATION));
    return EmailTemplates.enveloppErreur("Quota Resend proche de la limite", corps, baseUrl);
  }

  /**
   * Digest d'alerte pour les nouvelles entrées {@code logs_admin} de niveau erreur.
   *
   * @param erreurs liste des entrées d'erreur à afficher (non vide)
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML du digest d'erreurs
   */
  static String alerteLogsAdmin(final List<EntreeLogAdmin> erreurs, final String baseUrl) {
    final String lignesHtml =
        erreurs.stream()
            .map(
                entree -> {
                  final String dateFormatee =
                      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                          .withLocale(Locale.FRANCE)
                          .withZone(ZoneId.of("Europe/Paris"))
                          .format(Instant.ofEpochMilli(entree.ts()));
                  return """
                  <tr>
                    <td style="padding: 10px 0; border-bottom: 1px solid #f0f0f0; vertical-align: top">
                      <div style="font-weight: 600; color: %s; font-size: 14px">%s — %s</div>
                      <div style="color: #666; font-size: 13px; margin-top: 2px">%s</div>
                    </td>
                  </tr>
                  """
                      .formatted(
                          EmailTemplates.C_ERREUR,
                          EmailTemplates.e(dateFormatee),
                          EmailTemplates.e(entree.source()),
                          EmailTemplates.e(entree.message()));
                })
            .collect(Collectors.joining());
    final String corps =
        """
        <p style="color: %s; font-size: 14px; margin: 0 0 24px">%d nouvelle(s) entrée(s) d'erreur dans les logs admin :</p>
        <table style="border-collapse: collapse; width: 100%%">%s</table>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(
                EmailTemplates.C_TEXTE,
                erreurs.size(),
                lignesHtml,
                EmailTemplates.bouton(baseUrl + CHEMIN_ADMIN, "Voir les logs admin →"));
    return EmailTemplates.enveloppErreur("Nouvelles erreurs — logs admin", corps, baseUrl);
  }
}
