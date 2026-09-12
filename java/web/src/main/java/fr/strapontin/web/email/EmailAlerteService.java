/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import fr.strapontin.core.CompteurEnvoisResend;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Alertes email système (bounce, plainte spam, backup, worker inactif, digest logs_admin) —
 * extrait d'{@code EmailService}, qui reste dédié aux emails transactionnels utilisateur.
 *
 * <p>Si {@code RESEND_API_KEY} est absent de la configuration, toutes les méthodes retournent
 * silencieusement sans erreur. Les corps HTML sont générés par {@link EmailTemplates}.
 *
 * @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
@Service
public class EmailAlerteService {

  private static final Logger LOG = LoggerFactory.getLogger(EmailAlerteService.class);

  // Garde-fou anti-boucle : au plus une notification de bounce/plainte spam par heure,
  // persisté en app_config pour survivre aux redéploiements (même pattern que
  // AlerteLogsAdminService.CLE_CURSEUR) — sans ça, un problème de délivrabilité sur le nouveau
  // destinataire d'alerte pourrait recréer une boucle du même type que celle du 15/07/2026.
  private static final String CLE_DERNIERE_ALERTE_BOUNCE = "DERNIERE_ALERTE_BOUNCE_ENVOYEE";
  private static final String CLE_DERNIERE_ALERTE_SPAM = "DERNIERE_ALERTE_SPAM_ENVOYEE";
  private static final Duration INTERVALLE_MIN_ALERTE = Duration.ofMinutes(60);

  private final ConfigService configService;
  private final EmailSender emailSender;

  EmailAlerteService(final ConfigService configService, final EmailSender emailSender) {
    this.configService = configService;
    this.emailSender = emailSender;
  }

  /**
   * Retourne l'adresse destinataire des alertes système (bounce, plainte spam, worker inactif,
   * digest logs_admin) — distincte de l'expéditeur. Voir
   * {@link EmailSender#obtenirDestinataireAlerte()} pour la justification complète.
   *
   * @return l'adresse à notifier, vide si ni {@code ADMIN_ALERT_EMAIL} ni aucun admin n'existe
   */
  public Optional<String> obtenirDestinataireAlerte() {
    return emailSender.obtenirDestinataireAlerte();
  }

  /**
   * Indique si une alerte du type donné a déjà été envoyée il y a moins de
   * {@link #INTERVALLE_MIN_ALERTE} — garde-fou anti-boucle.
   */
  private boolean alerteRecente(final String cleConfig) {
    return configService
        .lireConfig(cleConfig)
        .map(Instant::parse)
        .map(
            derniere ->
                Duration.between(derniere, Instant.now()).compareTo(INTERVALLE_MIN_ALERTE) < 0)
        .orElse(false);
  }

  /**
   * Envoie une notification à l'admin quand un email Strapontin est rejeté en bounce.
   *
   * <p>Destinataire : {@link #obtenirDestinataireAlerte}, jamais l'expéditeur (c'est
   * cette confusion qui a provoqué la boucle de bounces du 15/07/2026). Throttlée à une
   * notification par heure maximum.
   *
   * @param emailBouncee l'adresse email qui a produit le bounce
   */
  public void envoyerNotificationBounce(final String emailBouncee) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — notification bounce non envoyée");
      return;
    }
    if (alerteRecente(CLE_DERNIERE_ALERTE_BOUNCE)) {
      LOG.debug("Notification bounce déjà envoyée récemment — ignorée (garde-fou anti-boucle)");
      return;
    }
    final Optional<String> destinataire = obtenirDestinataireAlerte();
    if (destinataire.isEmpty()) {
      LOG.warn("Aucun destinataire d'alerte disponible — notification bounce non envoyée");
      return;
    }

    final String corps = EmailTemplatesAlertes.alerteBounce(emailBouncee, ctx.get().baseUrl());

    try {
      emailSender.envoyer(
          ctx.get(),
          destinataire.get(),
          ctx.get().expediteur(),
          "Strapontin — bounce email : " + emailBouncee,
          corps,
          "Bounce détecté sur : "
              + emailBouncee
              + ". Consultez l'administration : "
              + ctx.get().baseUrl()
              + "/admin");
      configService.sauvegarderConfig(CLE_DERNIERE_ALERTE_BOUNCE, Instant.now().toString());
      LOG.info("Notification bounce envoyée : emailHash={}", emailBouncee.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi notification bounce", e);
    }
  }

  /**
   * Envoie une notification à l'admin quand un destinataire signale un email Strapontin comme
   * spam — même traitement que le bounce, une plainte spam menaçant la réputation
   * d'envoi du domaine au moins autant qu'un rejet définitif.
   *
   * <p>Destinataire : {@link #obtenirDestinataireAlerte}, jamais l'expéditeur.
   * Throttlée à une notification par heure maximum.
   *
   * @param emailPlaignant l'adresse email à l'origine de la plainte
   */
  public void envoyerNotificationPlainteSpam(final String emailPlaignant) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — notification plainte spam non envoyée");
      return;
    }
    if (alerteRecente(CLE_DERNIERE_ALERTE_SPAM)) {
      LOG.debug(
          "Notification plainte spam déjà envoyée récemment — ignorée (garde-fou anti-boucle)");
      return;
    }
    final Optional<String> destinataire = obtenirDestinataireAlerte();
    if (destinataire.isEmpty()) {
      LOG.warn("Aucun destinataire d'alerte disponible — notification plainte spam non envoyée");
      return;
    }

    final String corps = EmailTemplatesAlertes.alertePlainteSpam(emailPlaignant, ctx.get().baseUrl());

    try {
      emailSender.envoyer(
          ctx.get(),
          destinataire.get(),
          ctx.get().expediteur(),
          "Strapontin — plainte spam : " + emailPlaignant,
          corps,
          "Plainte spam signalée sur : "
              + emailPlaignant
              + ". Consultez l'administration : "
              + ctx.get().baseUrl()
              + "/admin");
      configService.sauvegarderConfig(CLE_DERNIERE_ALERTE_SPAM, Instant.now().toString());
      LOG.info("Notification plainte spam envoyée : emailHash={}", emailPlaignant.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi notification plainte spam", e);
    }
  }

  /**
   * Envoie une alerte à l'admin quand le backup nocturne a échoué.
   *
   * <p>Destinataire : {@link #obtenirDestinataireAlerte}, jamais l'expéditeur.
   *
   * @param horodatage l'horodatage du backup (format YYYYMMDD_HHMMSS), ou chaîne vide
   * @param raison     la commande bash ayant déclenché l'échec, ou chaîne vide
   */
  public void envoyerAlerteBackup(final String horodatage, final String raison) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — alerte backup non envoyée");
      return;
    }
    final Optional<String> destinataire = obtenirDestinataireAlerte();
    if (destinataire.isEmpty()) {
      LOG.warn("Aucun destinataire d'alerte disponible — alerte backup non envoyée");
      return;
    }

    final String corps = EmailTemplatesAlertes.alerteBackup(horodatage, raison, ctx.get().baseUrl());

    final String sujet = "Strapontin — échec backup nocturne";
    final String texte =
        "Échec du backup nocturne. Horodatage : %s.%s Consultez l'administration : %s/admin"
            .formatted(
                horodatage.isBlank() ? "inconnu" : horodatage,
                raison.isBlank() ? "" : " Raison : " + raison + ".",
                ctx.get().baseUrl());
    try {
      emailSender.envoyer(
          ctx.get(), destinataire.get(), ctx.get().expediteur(), sujet, corps, texte);
      LOG.info("Alerte backup envoyée");
    } catch (Exception e) {
      LOG.error("Échec envoi alerte backup", e);
    }
  }

  /**
   * Envoie une alerte à l'admin quand le contrôle d'intégrité AIDE détecte une modification de
   * fichier hors déploiement légitime sur un LXC prod/dev.
   *
   * <p>Destinataire : {@link #obtenirDestinataireAlerte}, jamais l'expéditeur.
   *
   * @param detail sortie résumée d'{@code aide --check}
   */
  public void envoyerAlerteIntegrite(final String detail) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — alerte intégrité non envoyée");
      return;
    }
    final Optional<String> destinataire = obtenirDestinataireAlerte();
    if (destinataire.isEmpty()) {
      LOG.warn("Aucun destinataire d'alerte disponible — alerte intégrité non envoyée");
      return;
    }

    final String corps = EmailTemplatesAlertes.alerteIntegrite(detail, ctx.get().baseUrl());
    final String sujet = "Strapontin — alerte contrôle d'intégrité";
    try {
      emailSender.envoyer(
          ctx.get(),
          destinataire.get(),
          ctx.get().expediteur(),
          sujet,
          corps,
          "Modification de fichier détectée hors déploiement légitime : "
              + detail
              + ". Consultez l'administration : "
              + ctx.get().baseUrl()
              + "/admin");
      LOG.info("Alerte intégrité envoyée");
    } catch (Exception e) {
      LOG.error("Échec envoi alerte intégrité", e);
    }
  }

  /**
   * Envoie une alerte à l'admin quand le worker est inactif depuis plus de 3 heures.
   *
   * @param email               l'adresse email de l'administrateur
   * @param derniereActiviteIso la date ISO du dernier job complété, ou null
   */
  public void envoyerAlerteWorkerInactif(final String email, final String derniereActiviteIso) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — alerte worker non envoyée");
      return;
    }

    final String detail =
        derniereActiviteIso != null
            ? "Dernier job complété : %s.".formatted(derniereActiviteIso)
            : "Aucun job complété enregistré.";

    final String corps = EmailTemplatesAlertes.alerteWorker(detail, ctx.get().baseUrl());

    final String sujet = "Strapontin — worker inactif";
    try {
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          sujet,
          corps,
          "Worker inactif — %s Consultez l'administration : %s/admin"
              .formatted(detail, ctx.get().baseUrl()));
      LOG.info("Alerte worker envoyée : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi alerte worker", e);
    }
  }

  /**
   * Envoie une alerte à l'admin quand le quota d'envoi Resend jour ou mois approche de sa
   * limite (80%, suite de l'incident).
   *
   * <p>Destinataire : {@link #obtenirDestinataireAlerte}, jamais l'expéditeur. Ne
   * consomme volontairement pas le garde-fou {@code GestionQuotaResend.reserverEnvoi()} avant
   * son propre envoi — au seuil de 80%, il reste toujours de la marge sous la limite dure.
   *
   * @param email    l'adresse email de l'administrateur
   * @param compteur le compteur courant ayant déclenché le seuil
   * @return {@code true} si l'email a effectivement été envoyé, {@code false} sinon
   *     (RESEND_API_KEY absent ou échec d'envoi) — dans ce cas l'appelant ne doit pas marquer
   *     l'alerte comme signalée, pour ne pas la perdre en cas de panne temporaire de Resend
   */
  public boolean envoyerAlerteQuotaResend(final String email, final CompteurEnvoisResend compteur) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — alerte quota Resend non envoyée");
      return false;
    }

    final String corps =
        EmailTemplatesAlertes.alerteQuotaResend(
            compteur.envoyesJour(),
            GestionQuotaResend.LIMITE_JOUR,
            compteur.envoyesMois(),
            GestionQuotaResend.LIMITE_MOIS,
            ctx.get().baseUrl());
    final String sujet = "Strapontin — quota Resend proche de la limite";
    try {
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          sujet,
          corps,
          "Quota Resend : jour %d/%d, mois %d/%d. Consultez l'administration : %s/admin"
              .formatted(
                  compteur.envoyesJour(),
                  GestionQuotaResend.LIMITE_JOUR,
                  compteur.envoyesMois(),
                  GestionQuotaResend.LIMITE_MOIS,
                  ctx.get().baseUrl()));
      LOG.info("Alerte quota Resend envoyée : emailHash={}", email.hashCode());
      return true;
    } catch (Exception e) {
      LOG.error("Échec envoi alerte quota Resend", e);
      return false;
    }
  }

  /**
   * Envoie un digest des nouvelles entrées {@code logs_admin} de niveau erreur.
   *
   * @param email   l'adresse email de l'administrateur
   * @param erreurs les entrées d'erreur à inclure dans le digest (non vide)
   * @return {@code true} si l'email a effectivement été envoyé, {@code false} sinon
   *     (RESEND_API_KEY absent ou échec d'envoi) — dans ce cas le curseur ne doit pas avancer
   *     côté appelant, pour ne perdre aucune erreur en cas de panne temporaire de Resend
   */
  public boolean envoyerAlerteLogsAdmin(
      final String email, final List<LogAdminService.EntreeLogAdmin> erreurs) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — alerte logs admin non envoyée");
      return false;
    }

    final String corps = EmailTemplatesAlertes.alerteLogsAdmin(erreurs, ctx.get().baseUrl());
    final String sujet = "Strapontin — " + erreurs.size() + " nouvelle(s) erreur(s) admin";
    try {
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          sujet,
          corps,
          erreurs.size()
              + " nouvelle(s) erreur(s) dans logs_admin. Consultez l'administration : "
              + ctx.get().baseUrl()
              + "/admin");
      LOG.info("Alerte logs admin envoyée : emailHash={} nb={}", email.hashCode(), erreurs.size());
      return true;
    } catch (Exception e) {
      LOG.error("Échec envoi alerte logs admin", e);
      return false;
    }
  }
}
