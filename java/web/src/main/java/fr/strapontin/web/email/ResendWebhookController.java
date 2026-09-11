/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resend.Resend;
import com.resend.services.webhooks.model.VerifyWebhookOptions;
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

/**
 * Endpoint de réception des webhooks Resend.
 *
 * Le endpoint est public (non authentifié) mais protégé par vérification HMAC via la librairie
 * Svix embarquée dans le SDK resend-java.
 *
 * La clé de signature doit être configurée dans {@code app_config} sous la clé {@code RESEND_WEBHOOK_SECRET}
 * (visible depuis {@code /admin}).
 *
 * @since 2.1.0
 * @author Kwaite
 */
@RestController
public class ResendWebhookController {

  private static final Logger log = LoggerFactory.getLogger(ResendWebhookController.class);

  private final ConfigService configService;
  private final EmailAlerteService emailAlerteService;
  private final ObjectMapper objectMapper;
  private final LogAdminService logAdminService;

  public ResendWebhookController(
      ConfigService configService,
      EmailAlerteService emailAlerteService,
      ObjectMapper objectMapper,
      LogAdminService logAdminService) {
    this.configService = configService;
    this.emailAlerteService = emailAlerteService;
    this.objectMapper = objectMapper;
    this.logAdminService = logAdminService;
  }

  /**
   * Reçoit et traite un événement webhook Resend.
   *
   * Retourne 400 si la clé {@code RESEND_WEBHOOK_SECRET} n'est pas configurée, 401 si la signature Svix
   * est invalide, 200 sinon.
   *
   * @param svixId header {@code svix-id} (identifiant unique de l'événement)
   * @param svixTimestamp header {@code svix-timestamp}
   * @param svixSignature header {@code svix-signature}
   * @param corpsRaw corps brut de la requête (sensible à la moindre modification)
   * @return réponse HTTP
   */
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
    } catch (Exception e) {
      // Exception large (pas seulement ResendException) : la vérification Svix embarquée lève
      // aussi une NumberFormatException non enveloppée sur un svix-timestamp non numérique
      // sans ce catch, l'exception technique remonte en 500 (au lieu du 401 attendu
      // pour une signature invalide) et déclenche une alerte email admin parasite.
      log.warn("Signature webhook Resend invalide : {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    traiterEvenement(corpsRaw, svixId);
    return ResponseEntity.ok().build();
  }

  /**
   * Traite un événement webhook Resend déjà authentifié (signature Svix vérifiée).
   *
   * <p>Visibilité package-private pour permettre un test unitaire direct — la vérification de
   * signature Svix ({@link #recevoirEvenement}) ne peut pas être simulée simplement en test.
   *
   * @param corpsRaw corps JSON brut de l'événement
   * @param svixId identifiant de l'événement (header {@code svix-id}), mémorisé dans le meta
   *   {@code logs_admin} pour permettre un rejeu ciblé depuis l'admin
   */
  void traiterEvenement(String corpsRaw, String svixId) {
    try {
      JsonNode racine = objectMapper.readTree(corpsRaw);
      String type = racine.path("type").asText("");
      String emailDestinataire = racine.path("data").path("to").path(0).asText("<inconnu>");

      // email.bounced et email.complained (plainte spam) reçoivent le même traitement :
      // une plainte spam menace la réputation d'envoi du domaine au moins autant qu'un bounce.
      if ("email.bounced".equals(type)) {
        traiterIncidentEmail("Bounce", "bounce email reçu", emailDestinataire, svixId);
        emailAlerteService.envoyerNotificationBounce(emailDestinataire);
      } else if ("email.complained".equals(type)) {
        traiterIncidentEmail("Plainte spam", "plainte spam email reçue", emailDestinataire, svixId);
        emailAlerteService.envoyerNotificationPlainteSpam(emailDestinataire);
      }
    } catch (Exception e) {
      log.error("Erreur lors du traitement du webhook Resend", e);
    }
  }

  /**
   * Journalise un incident email (bounce ou plainte spam) avec l'adresse hachée — jamais en clair
   * dans {@code logs_admin}, cohérent avec le hachage déjà appliqué côté SLF4J. Mémorise aussi
   * {@code svixId} : seul identifiant permettant de retrouver puis rejouer cet événement précis
   * via l'API Resend (cf. {@code ResendWebhooksAdminService}).
   */
  private void traiterIncidentEmail(
      String libelle, String messageLogAdmin, String email, String svixId) {
    String emailHash = String.valueOf(email.hashCode());
    log.warn("{} email Resend : emailHash={}", libelle, emailHash);
    logAdminService.logAdmin(
        "warn", "web", messageLogAdmin, Map.of("email", emailHash, "svixId", nvl(svixId)));
  }

  private static String nvl(String valeur) {
    return valeur != null ? valeur : "";
  }
}
