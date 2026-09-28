/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.usage.model.UsageEmails;
import fr.strapontin.core.CompteurEnvoisResend;
import fr.strapontin.web.config.ConfigService;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Infrastructure d'envoi Resend partagée par {@link EmailService}
 * et {@link EmailAlerteService} (détail : CLAUDE.md, section
 * Resend).
 * @since 2.7.0
 * @author Kwaite
 */
@Component
class EmailSender {

  private static final Logger LOG = LoggerFactory.getLogger(EmailSender.class);

  private final ConfigService configService;
  private final JdbcTemplate jdbcTemplate;
  private final GestionQuotaResend gestionQuotaResend;

  /* package-private : instancié uniquement par
   * EmailService/EmailServiceAdmin/EmailAlerteService (même package). */
  EmailSender(
      final ConfigService configService,
      final JdbcTemplate jdbcTemplate,
      final GestionQuotaResend gestionQuotaResend) {
    this.configService = configService;
    this.jdbcTemplate = jdbcTemplate;
    this.gestionQuotaResend = gestionQuotaResend;
  }

  /* package-private : type de retour interne, utilisé par
   * EmailService/EmailServiceAdmin/EmailAlerteService (même package). */
  record ContexteEmail(Resend client, String expediteur, String baseUrl) {}

  /* package-private : appelé par
   * EmailService/EmailServiceAdmin/EmailAlerteService (même package). */
  Optional<ContexteEmail> obtenirContexte() {
    final Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
    if (cle.isEmpty()) {
      return Optional.empty();
    }
    final String expediteur =
        configService.lireConfig("CONTACT_EMAIL").or(this::obtenirEmailPremierAdmin).orElse("");
    final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    return Optional.of(new ContexteEmail(new Resend(cle.get()), expediteur, baseUrl));
  }

  private Optional<String> obtenirEmailPremierAdmin() {
    try {
      return Optional.ofNullable(
          jdbcTemplate.queryForObject(
              "SELECT email FROM users WHERE role = 'admin' ORDER BY created_at LIMIT 1",
              String.class));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /**
   * Retourne l'adresse destinataire des alertes système,
   * distincte de l'expéditeur (détail : CLAUDE.md, section
   * Resend).
   * @return l'adresse à notifier, vide si aucune résolution
   */
  /* package-private : appelé par
   * EmailService/EmailServiceAdmin/EmailAlerteService (même package). */
  Optional<String> obtenirDestinataireAlerte() {
    return configService.lireConfig("ADMIN_ALERT_EMAIL").or(this::obtenirEmailPremierAdmin);
  }

  /* package-private : appelé par
   * EmailService/EmailServiceAdmin/EmailAlerteService (même package) ; renvoie
   * false si le quota a bloqué l'envoi. */
  boolean envoyer(
      final ContexteEmail ctx,
      final String destinataire,
      final String replyTo,
      final String sujet,
      final String corps,
      final String texte) {
    if (!gestionQuotaResend.reserverEnvoi()) {
      LOG.warn("quota Resend atteint — envoi ignoré destinataireHash={}", destinataire.hashCode());
      return false;
    }
    try {
      final CreateEmailOptions options =
          CreateEmailOptions.builder()
              .from(ctx.expediteur())
              .replyTo(replyTo)
              .to(List.of(destinataire))
              .subject(sujet)
              .html(corps)
              .text(texte)
              .build();
      ctx.client().emails().send(options);
      return true;
    } catch (ResendException e) {
      throw new IllegalStateException("Erreur d'envoi email", e);
    }
  }

  /**
   * Recale le compteur de quota local sur la consommation réelle du compte
   * Resend ({@code GET /usage}) — sans effet si Resend n'est pas configuré ou
   * ne répond pas (le compteur local reste alors la seule référence).
   */
  /* package-private : appelé par AlerteQuotaResendService (même package). */
  void recalerQuotaSurUsageReel() {
    final Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      return;
    }
    try {
      final UsageEmails emails = ctx.get().client().usage().get().getEmails();
      if (emails == null || emails.getDaily() == null || emails.getMonthly() == null) {
        return;
      }
      gestionQuotaResend.recalerSurUsageReel(
          valeurOuZero(emails.getDaily().getUsed()), valeurOuZero(emails.getMonthly().getUsed()));
    } catch (ResendException e) {
      LOG.warn("usage Resend indisponible, compteur local conservé : {}", e.getMessage());
    }
  }

  private static int valeurOuZero(final Integer valeur) {
    return valeur != null ? valeur : 0;
  }

  /**
   * Nombre d'envois encore autorisés par le quota, recalé au préalable sur la
   * consommation réelle Resend.
   * @return envois restants avant blocage
   */
  /* package-private : appelé par EmailServiceSecurite (même package). */
  int envoisRestants() {
    recalerQuotaSurUsageReel();
    final CompteurEnvoisResend compteur = gestionQuotaResend.lireCompteurCourant();
    return Math.max(
        0,
        Math.min(
            GestionQuotaResend.LIMITE_JOUR - compteur.envoyesJour(),
            GestionQuotaResend.LIMITE_MOIS - compteur.envoyesMois()));
  }
}
