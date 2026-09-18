/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
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
   * EmailService/EmailServiceAdmin/EmailAlerteService (même package). */
  void envoyer(
      final ContexteEmail ctx,
      final String destinataire,
      final String replyTo,
      final String sujet,
      final String corps,
      final String texte) {
    if (!gestionQuotaResend.reserverEnvoi()) {
      LOG.warn("quota Resend atteint — envoi ignoré destinataireHash={}", destinataire.hashCode());
      return;
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
    } catch (ResendException e) {
      throw new IllegalStateException("Erreur d'envoi email", e);
    }
  }
}
