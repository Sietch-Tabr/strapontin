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

/// Alertes email système (bounce, plainte spam, backup, worker inactif, digest logs_admin) —
/// extrait d'{@code EmailService} (#925), qui reste dédié aux emails transactionnels utilisateur.
///
/// <p>Si `RESEND_API_KEY` est absent de la configuration, toutes les méthodes retournent
/// silencieusement sans erreur. Les corps HTML sont générés par {@link EmailTemplates}.
///
/// @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
/// @author Kwaite
@Service
public class EmailAlerteService {

  private static final Logger log = LoggerFactory.getLogger(EmailAlerteService.class);

  // Garde-fou anti-boucle (#907) : au plus une notification de bounce/plainte spam par heure,
  // persisté en app_config pour survivre aux redéploiements (même pattern que
  // AlerteLogsAdminService.CLE_CURSEUR) — sans ça, un problème de délivrabilité sur le nouveau
  // destinataire d'alerte pourrait recréer une boucle du même type que celle du 15/07/2026.
  private static final String CLE_DERNIERE_ALERTE_BOUNCE = "DERNIERE_ALERTE_BOUNCE_ENVOYEE";
  private static final String CLE_DERNIERE_ALERTE_SPAM = "DERNIERE_ALERTE_SPAM_ENVOYEE";
  private static final Duration INTERVALLE_MIN_ALERTE = Duration.ofMinutes(60);

  private final ConfigService configService;
  private final EmailSender emailSender;

  EmailAlerteService(ConfigService configService, EmailSender emailSender) {
    this.configService = configService;
    this.emailSender = emailSender;
  }

  /// Retourne l'adresse destinataire des alertes système (bounce, plainte spam, worker inactif,
  /// digest logs_admin) — distincte de l'expéditeur (#907). Voir
  /// {@link EmailSender#obtenirDestinataireAlerte()} pour la justification complète.
  ///
  /// @return l'adresse à notifier, vide si ni `ADMIN_ALERT_EMAIL` ni aucun admin n'existe
  public Optional<String> obtenirDestinataireAlerte() {
    return emailSender.obtenirDestinataireAlerte();
  }

  /// Indique si une alerte du type donné a déjà été envoyée il y a moins de
  /// {@link #INTERVALLE_MIN_ALERTE} — garde-fou anti-boucle (#907).
  private boolean alerteRecente(String cleConfig) {
    return configService
        .lireConfig(cleConfig)
        .map(Instant::parse)
        .map(
            derniere ->
                Duration.between(derniere, Instant.now()).compareTo(INTERVALLE_MIN_ALERTE) < 0)
        .orElse(false);
  }

  /// Envoie une notification à l'admin quand un email Strapontin est rejeté en bounce.
  ///
  /// <p>Destinataire : {@link #obtenirDestinataireAlerte()}, jamais l'expéditeur (#907 — c'est
  /// cette confusion qui a provoqué la boucle de bounces du 15/07/2026). Throttlée à une
  /// notification par heure maximum.
  ///
  /// @param emailBouncee l'adresse email qui a produit le bounce
  public void envoyerNotificationBounce(String emailBouncee) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — notification bounce non envoyée");
      return;
    }
    if (alerteRecente(CLE_DERNIERE_ALERTE_BOUNCE)) {
      log.debug("Notification bounce déjà envoyée récemment — ignorée (garde-fou anti-boucle)");
      return;
    }
    Optional<String> destinataire = obtenirDestinataireAlerte();
    if (destinataire.isEmpty()) {
      log.warn("Aucun destinataire d'alerte disponible — notification bounce non envoyée");
      return;
    }

    String corps = EmailTemplates.alerteBounce(emailBouncee, ctx.get().baseUrl());

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
      log.info("Notification bounce envoyée : emailHash={}", emailBouncee.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi notification bounce", e);
    }
  }

  /// Envoie une notification à l'admin quand un destinataire signale un email Strapontin comme
  /// spam (#832) — même traitement que le bounce, une plainte spam menaçant la réputation
  /// d'envoi du domaine au moins autant qu'un rejet définitif.
  ///
  /// <p>Destinataire : {@link #obtenirDestinataireAlerte()}, jamais l'expéditeur (#907).
  /// Throttlée à une notification par heure maximum.
  ///
  /// @param emailPlaignant l'adresse email à l'origine de la plainte
  public void envoyerNotificationPlainteSpam(String emailPlaignant) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — notification plainte spam non envoyée");
      return;
    }
    if (alerteRecente(CLE_DERNIERE_ALERTE_SPAM)) {
      log.debug(
          "Notification plainte spam déjà envoyée récemment — ignorée (garde-fou anti-boucle)");
      return;
    }
    Optional<String> destinataire = obtenirDestinataireAlerte();
    if (destinataire.isEmpty()) {
      log.warn("Aucun destinataire d'alerte disponible — notification plainte spam non envoyée");
      return;
    }

    String corps = EmailTemplates.alertePlainteSpam(emailPlaignant, ctx.get().baseUrl());

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
      log.info("Notification plainte spam envoyée : emailHash={}", emailPlaignant.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi notification plainte spam", e);
    }
  }

  /// Envoie une alerte à l'admin quand le backup nocturne a échoué.
  ///
  /// <p>Destinataire : {@link #obtenirDestinataireAlerte()}, jamais l'expéditeur (#907).
  ///
  /// @param horodatage l'horodatage du backup (format YYYYMMDD_HHMMSS), ou chaîne vide
  /// @param raison     la commande bash ayant déclenché l'échec, ou chaîne vide
  public void envoyerAlerteBackup(String horodatage, String raison) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — alerte backup non envoyée");
      return;
    }
    Optional<String> destinataire = obtenirDestinataireAlerte();
    if (destinataire.isEmpty()) {
      log.warn("Aucun destinataire d'alerte disponible — alerte backup non envoyée");
      return;
    }

    String corps = EmailTemplates.alerteBackup(horodatage, raison, ctx.get().baseUrl());

    String sujet = "Strapontin — échec backup nocturne";
    String texte =
        "Échec du backup nocturne. Horodatage : %s.%s Consultez l'administration : %s/admin"
            .formatted(
                horodatage.isBlank() ? "inconnu" : horodatage,
                raison.isBlank() ? "" : " Raison : " + raison + ".",
                ctx.get().baseUrl());
    try {
      emailSender.envoyer(
          ctx.get(), destinataire.get(), ctx.get().expediteur(), sujet, corps, texte);
      log.info("Alerte backup envoyée");
    } catch (Exception e) {
      log.error("Échec envoi alerte backup", e);
    }
  }

  /// Envoie une alerte à l'admin quand le worker est inactif depuis plus de 3 heures.
  ///
  /// @param email               l'adresse email de l'administrateur
  /// @param derniereActiviteIso la date ISO du dernier job complété, ou null
  public void envoyerAlerteWorkerInactif(String email, String derniereActiviteIso) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — alerte worker non envoyée");
      return;
    }

    String detail =
        derniereActiviteIso != null
            ? "Dernier job complété : %s.".formatted(derniereActiviteIso)
            : "Aucun job complété enregistré.";

    String corps = EmailTemplates.alerteWorker(detail, ctx.get().baseUrl());

    String sujet = "Strapontin — worker inactif";
    try {
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          sujet,
          corps,
          "Worker inactif — %s Consultez l'administration : %s/admin"
              .formatted(detail, ctx.get().baseUrl()));
      log.info("Alerte worker envoyée : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi alerte worker", e);
    }
  }

  /// Envoie une alerte à l'admin quand le quota d'envoi Resend jour ou mois approche de sa
  /// limite (80%, #981, suite de l'incident #907).
  ///
  /// <p>Destinataire : {@link #obtenirDestinataireAlerte()}, jamais l'expéditeur (#907). Ne
  /// consomme volontairement pas le garde-fou {@code GestionQuotaResend.reserverEnvoi()} avant
  /// son propre envoi — au seuil de 80%, il reste toujours de la marge sous la limite dure.
  ///
  /// @param email    l'adresse email de l'administrateur
  /// @param compteur le compteur courant ayant déclenché le seuil
  /// @return {@code true} si l'email a effectivement été envoyé, {@code false} sinon
  ///     (RESEND_API_KEY absent ou échec d'envoi) — dans ce cas l'appelant ne doit pas marquer
  ///     l'alerte comme signalée, pour ne pas la perdre en cas de panne temporaire de Resend
  public boolean envoyerAlerteQuotaResend(String email, CompteurEnvoisResend compteur) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — alerte quota Resend non envoyée");
      return false;
    }

    String corps =
        EmailTemplates.alerteQuotaResend(
            compteur.envoyesJour(),
            GestionQuotaResend.LIMITE_JOUR,
            compteur.envoyesMois(),
            GestionQuotaResend.LIMITE_MOIS,
            ctx.get().baseUrl());
    String sujet = "Strapontin — quota Resend proche de la limite";
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
      log.info("Alerte quota Resend envoyée : emailHash={}", email.hashCode());
      return true;
    } catch (Exception e) {
      log.error("Échec envoi alerte quota Resend", e);
      return false;
    }
  }

  /// Envoie un digest des nouvelles entrées {@code logs_admin} de niveau erreur (#765).
  ///
  /// @param email   l'adresse email de l'administrateur
  /// @param erreurs les entrées d'erreur à inclure dans le digest (non vide)
  /// @return {@code true} si l'email a effectivement été envoyé, {@code false} sinon
  ///     (RESEND_API_KEY absent ou échec d'envoi) — dans ce cas le curseur ne doit pas avancer
  ///     côté appelant, pour ne perdre aucune erreur en cas de panne temporaire de Resend
  public boolean envoyerAlerteLogsAdmin(
      String email, List<LogAdminService.EntreeLogAdmin> erreurs) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — alerte logs admin non envoyée");
      return false;
    }

    String corps = EmailTemplates.alerteLogsAdmin(erreurs, ctx.get().baseUrl());
    String sujet = "Strapontin — " + erreurs.size() + " nouvelle(s) erreur(s) admin";
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
      log.info("Alerte logs admin envoyée : emailHash={} nb={}", email.hashCode(), erreurs.size());
      return true;
    } catch (Exception e) {
      log.error("Échec envoi alerte logs admin", e);
      return false;
    }
  }
}
