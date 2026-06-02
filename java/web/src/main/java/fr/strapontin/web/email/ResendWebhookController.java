/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.webhooks.model.VerifyWebhookOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/// Endpoint de réception des webhooks Resend.
///
/// Le endpoint est public (non authentifié) mais protégé par vérification HMAC
/// via la librairie Svix embarquée dans le SDK resend-java.
///
/// La clé de signature doit être configurée dans `app_config` sous la clé
/// `RESEND_WEBHOOK_SECRET` (visible depuis `/admin`).
///
/// @since 2.1.0
/// @author Kwaite
@RestController
public class ResendWebhookController {

  private static final Logger log = LoggerFactory.getLogger(ResendWebhookController.class);

  private final ConfigService configService;
  private final EmailService emailService;
  private final ObjectMapper objectMapper;
  private final LogAdminService logAdminService;

  public ResendWebhookController(
      ConfigService configService,
      EmailService emailService,
      ObjectMapper objectMapper,
      LogAdminService logAdminService) {
    this.configService = configService;
    this.emailService = emailService;
    this.objectMapper = objectMapper;
    this.logAdminService = logAdminService;
  }

  /// Reçoit et traite un événement webhook Resend.
  ///
  /// Retourne 400 si la clé `RESEND_WEBHOOK_SECRET` n'est pas configurée,
  /// 401 si la signature Svix est invalide, 200 sinon.
  ///
  /// @param svixId        header `svix-id` (identifiant unique de l'événement)
  /// @param svixTimestamp header `svix-timestamp`
  /// @param svixSignature header `svix-signature`
  /// @param corpsRaw      corps brut de la requête (sensible à la moindre modification)
  /// @return réponse HTTP
  @PostMapping(value = "/api/webhooks/resend", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> recevoirEvenement(
      @RequestHeader(value = "svix-id", required = false) String svixId,
      @RequestHeader(value = "svix-timestamp", required = false) String svixTimestamp,
      @RequestHeader(value = "svix-signature", required = false) String svixSignature,
      @RequestBody String corpsRaw) {

    Optional<String> secret = configService.lireConfig("RESEND_WEBHOOK_SECRET");
    if (secret.isEmpty()) {
      log.warn("RESEND_WEBHOOK_SECRET absent — webhook Resend rejeté");
      return ResponseEntity.badRequest().build();
    }

    try {
      new Resend("")
          .webhooks()
          .verify(
              VerifyWebhookOptions.builder()
                  .payload(corpsRaw)
                  .addHeaders(
                      Map.of(
                          "svix-id", nvl(svixId),
                          "svix-timestamp", nvl(svixTimestamp),
                          "svix-signature", nvl(svixSignature)))
                  .secret(secret.get())
                  .build());
    } catch (ResendException e) {
      log.warn("Signature webhook Resend invalide : {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    traiterEvenement(corpsRaw);
    return ResponseEntity.ok().build();
  }

  private void traiterEvenement(String corpsRaw) {
    try {
      JsonNode racine = objectMapper.readTree(corpsRaw);
      String type = racine.path("type").asText("");

      if ("email.bounced".equals(type)) {
        String emailDestinataire = racine.path("data").path("to").path(0).asText("<inconnu>");
        log.warn("Bounce email Resend : {}", emailDestinataire);
        logAdminService.logAdmin(
            "warn", "web", "bounce email reçu", Map.of("email", emailDestinataire));
        notifierAdminBounce(emailDestinataire);
      } else if ("email.complained".equals(type)) {
        String emailDestinataire = racine.path("data").path("to").path(0).asText("<inconnu>");
        log.warn("Plainte email Resend (spam) : {}", emailDestinataire);
      }
    } catch (Exception e) {
      log.error("Erreur lors du traitement du webhook Resend", e);
    }
  }

  private void notifierAdminBounce(String emailBouncee) {
    emailService.envoyerNotificationBounce(emailBouncee);
  }

  private static String nvl(String valeur) {
    return valeur != null ? valeur : "";
  }
}
