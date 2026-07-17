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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/// Infrastructure d'envoi Resend partagée par {@link EmailService} (emails transactionnels) et
/// {@link EmailAlerteService} (alertes système) — extrait lors de leur scission (#925) pour
/// éviter de dupliquer le contexte Resend (client, expéditeur, URL de base) et la résolution du
/// destinataire d'alerte entre les deux classes.
///
/// @since 2.7.0
/// @author Kwaite
@Component
class EmailSender {

  private final ConfigService configService;
  private final JdbcTemplate jdbcTemplate;

  EmailSender(ConfigService configService, JdbcTemplate jdbcTemplate) {
    this.configService = configService;
    this.jdbcTemplate = jdbcTemplate;
  }

  record ContexteEmail(Resend client, String expediteur, String baseUrl) {}

  Optional<ContexteEmail> obtenirContexte() {
    Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
    if (cle.isEmpty()) {
      return Optional.empty();
    }
    String expediteur =
        configService.lireConfig("CONTACT_EMAIL").or(this::obtenirEmailPremierAdmin).orElse("");
    String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
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

  /// Retourne l'adresse destinataire des alertes système (bounce, plainte spam, worker inactif,
  /// digest logs_admin) — distincte de l'expéditeur (#907).
  ///
  /// <p>Priorité à `ADMIN_ALERT_EMAIL` si configurée, sinon repli sur le premier admin. Ne se
  /// replie volontairement jamais sur `CONTACT_EMAIL`/l'expéditeur : c'est cette confusion qui a
  /// provoqué la boucle de bounces du 15/07/2026 (une adresse `noreply` sans boîte de réception
  /// réutilisée comme destinataire).
  ///
  /// @return l'adresse à notifier, vide si ni `ADMIN_ALERT_EMAIL` ni aucun admin n'existe
  Optional<String> obtenirDestinataireAlerte() {
    return configService.lireConfig("ADMIN_ALERT_EMAIL").or(this::obtenirEmailPremierAdmin);
  }

  void envoyer(
      ContexteEmail ctx,
      String destinataire,
      String replyTo,
      String sujet,
      String corps,
      String texte) {
    try {
      CreateEmailOptions options =
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
