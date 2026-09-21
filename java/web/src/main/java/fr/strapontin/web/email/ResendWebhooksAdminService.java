/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.suppressions.model.ListSuppressionsResponseSuccess;
import com.resend.services.webhooks.dto.WebhookDTO;
import com.resend.services.webhooks.dto.WebhookEventAttemptDTO;
import com.resend.services.webhooks.model.ListWebhookEventAttemptsResponseSuccess;
import com.resend.services.webhooks.model.ListWebhookEventsResponseSuccess;
import com.resend.services.webhooks.model.ListWebhooksResponseSuccess;
import com.resend.services.webhooks.model.RotateWebhookSigningSecretResponseSuccess;
import fr.strapontin.web.config.ConfigService;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Liste et rejoue les événements du webhook Resend configuré
 * (détail : CLAUDE.md, Configuration applicative).
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class ResendWebhooksAdminService {

  private static final Logger LOG = LoggerFactory.getLogger(ResendWebhooksAdminService.class);
  private static final String CLE_RESEND_API_KEY = "RESEND_API_KEY";

  private final ConfigService configService;
  private final Function<String, Resend> fabriqueResend;

  /**
   * @param configService lecture de la clé API Resend
   */
  @Autowired
  public ResendWebhooksAdminService(final ConfigService configService) {
    this(configService, Resend::new);
  }

  /**
   * Constructeur avec fabrique de client injectable — le SDK resend-java ne
   * fournit aucun point d'injection HTTP public (contrairement à
   * {@code RestClient} pour les autres sources), la fabrique est le seul moyen
   * de substituer un client {@link Resend} mocké en test.
   */
  /* package-private : injection de test (fabrique Resend personnalisée). */
  ResendWebhooksAdminService(
      final ConfigService configService, final Function<String, Resend> fabriqueResend) {
    this.configService = configService;
    this.fabriqueResend = fabriqueResend;
  }

  /**
   * Un événement webhook Resend, tel qu'affiché dans l'admin.
   * @param id identifiant de l'événement (svix-id)
   * @param type type d'événement (ex. {@code "email.bounced"})
   * @param statut statut de livraison, ou {@code null} si inconnu
   * @param creeLe horodatage de création (format ISO Resend)
   */
  public record EvenementWebhook(String id, String type, String statut, String creeLe) {}

  /**
   * Liste les événements récents du webhook configuré.
   *
   * @return la liste des événements, vide si {@code RESEND_API_KEY} /{@code
   *     PUBLIC_BASE_URL} absent, aucun webhook ne correspond, ou en cas
   *     d'erreur API (journalisée, jamais propagée)
   */
  public List<EvenementWebhook> listerEvenements() {
    final Optional<String> cle = configService.lireConfig(CLE_RESEND_API_KEY);
    if (cle.isEmpty()) {
      return List.of();
    }
    final Resend client = fabriqueResend.apply(cle.get());

    final String webhookId = resoudreWebhookId(client);
    if (webhookId == null) {
      return List.of();
    }

    try {
      final ListWebhookEventsResponseSuccess reponse = client.webhooks().listEvents(webhookId);
      return reponse.getData() != null
          ? reponse.getData().stream()
              .map(
                  e ->
                      new EvenementWebhook(
                          e.getId(),
                          e.getType(),
                          e.getStatus() != null ? e.getStatus().getValue() : null,
                          e.getCreatedAt()))
              .toList()
          : List.of();
    } catch (ResendException e) {
      LOG.warn("Erreur listing événements webhook Resend : {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * Rejoue un événement webhook en échec (statut {@code failed} ).
   *
   * @param eventId identifiant de l'événement à rejouer
   * @return {@code true} si le rejeu a été accepté par Resend, {@code false}
   *     sinon (configuration absente, webhook introuvable, erreur API)
   */
  public boolean rejouerEvenement(final String eventId) {
    final Optional<String> cle = configService.lireConfig(CLE_RESEND_API_KEY);
    if (cle.isEmpty()) {
      return false;
    }
    final Resend client = fabriqueResend.apply(cle.get());

    final String webhookId = resoudreWebhookId(client);
    if (webhookId == null) {
      return false;
    }

    try {
      client.webhooks().replayEvent(webhookId, eventId);
      LOG.info("rejeu événement webhook Resend id={}", eventId);
      return true;
    } catch (ResendException e) {
      LOG.warn("Erreur rejeu événement webhook Resend id={} : {}", eventId, e.getMessage());
      return false;
    }
  }

  /**
   * Un événement webhook Resend, tel qu'affiché dans l'admin.
   * @param id identifiant de la tentative
   * @param httpStatusCode code HTTP retourné par l'endpoint, ou {@code null}
   * @param reponse corps de la réponse de l'endpoint, ou {@code null}
   * @param envoyeeLe horodatage de l'envoi (format ISO Resend)
   */
  public record TentativeWebhook(
      String id, Integer httpStatusCode, String reponse, String envoyeeLe) {}

  /**
   * Une adresse email sur la liste de suppression Resend.
   * @param id identifiant de l'entrée
   * @param email adresse supprimée
   * @param origine raison de l'ajout (ex. {@code "bounce"}, {@code "manual"})
   * @param creeLe horodatage de création (format ISO Resend)
   */
  public record EntreeSuppression(String id, String email, String origine, String creeLe) {}

  /**
   * Fait tourner le secret de signature du webhook configuré et persiste
   * le nouveau secret — sans repasser par le dashboard Resend.
   *
   * @return le nouveau secret, ou {@link Optional#empty()} si {@code
   *     RESEND_API_KEY}/{@code PUBLIC_BASE_URL} absent, webhook introuvable,
   *     ou erreur API
   */
  public Optional<String> rotationSecretWebhook() {
    final Optional<String> cle = configService.lireConfig(CLE_RESEND_API_KEY);
    if (cle.isEmpty()) {
      return Optional.empty();
    }
    final Resend client = fabriqueResend.apply(cle.get());

    final String webhookId = resoudreWebhookId(client);
    if (webhookId == null) {
      return Optional.empty();
    }

    try {
      final RotateWebhookSigningSecretResponseSuccess reponse =
          client.webhooks().rotateSigningSecret(webhookId);
      final String nouveauSecret = reponse.getSigningSecret();
      if (nouveauSecret == null) {
        return Optional.empty();
      }
      configService.sauvegarderConfig("RESEND_WEBHOOK_SECRET", nouveauSecret);
      LOG.info("Secret de signature webhook Resend renouvelé");
      return Optional.of(nouveauSecret);
    } catch (ResendException e) {
      LOG.warn("Erreur rotation secret webhook Resend : {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Liste les tentatives de livraison d'un événement webhook.
   *
   * @param eventId identifiant de l'événement
   * @return les tentatives, vide si configuration absente, webhook/événement
   *     introuvable, ou erreur API
   */
  public List<TentativeWebhook> listerTentatives(final String eventId) {
    final Optional<String> cle = configService.lireConfig(CLE_RESEND_API_KEY);
    if (cle.isEmpty()) {
      return List.of();
    }
    final Resend client = fabriqueResend.apply(cle.get());

    final String webhookId = resoudreWebhookId(client);
    if (webhookId == null) {
      return List.of();
    }

    try {
      final ListWebhookEventAttemptsResponseSuccess reponse =
          client.webhooks().listEventAttempts(webhookId, eventId);
      return reponse.getData() != null
          ? reponse.getData().stream().map(this::versTentative).toList()
          : List.of();
    } catch (ResendException e) {
      LOG.warn("Erreur listing tentatives webhook Resend eventId={} : {}", eventId, e.getMessage());
      return List.of();
    }
  }

  private TentativeWebhook versTentative(final WebhookEventAttemptDTO dto) {
    return new TentativeWebhook(
        dto.getId(), dto.getHttpStatusCode(), dto.getResponse(), dto.getSentAt());
  }

  /**
   * Liste les adresses sur la liste de suppression Resend (bounces/plaintes
   * bloquant définitivement l'envoi tant qu'elles n'en sont pas retirées).
   *
   * @return les entrées, vide si {@code RESEND_API_KEY} absent ou erreur API
   */
  public List<EntreeSuppression> listerSuppressions() {
    final Optional<String> cle = configService.lireConfig(CLE_RESEND_API_KEY);
    if (cle.isEmpty()) {
      return List.of();
    }
    final Resend client = fabriqueResend.apply(cle.get());

    try {
      final ListSuppressionsResponseSuccess reponse = client.suppressions().list();
      return reponse.getData() != null
          ? reponse.getData().stream()
              .map(
                  s ->
                      new EntreeSuppression(
                          s.getId(), s.getEmail(), s.getOrigin(), s.getCreatedAt()))
              .toList()
          : List.of();
    } catch (ResendException e) {
      LOG.warn("Erreur listing suppressions Resend : {}", e.getMessage());
      return List.of();
    }
  }

  private String resoudreWebhookId(final Resend client) {
    final Optional<String> baseUrl = configService.lireConfig("PUBLIC_BASE_URL");
    if (baseUrl.isEmpty()) {
      return null;
    }
    final String endpointAttendu = baseUrl.get() + "/api/webhooks/resend";

    try {
      final ListWebhooksResponseSuccess reponse = client.webhooks().list();
      if (reponse.getData() == null) {
        return null;
      }
      return reponse.getData().stream()
          .filter(w -> endpointAttendu.equals(w.getEndpoint()))
          .map(WebhookDTO::getId)
          .findFirst()
          .orElse(null);
    } catch (ResendException e) {
      LOG.warn("Erreur résolution du webhookId Resend : {}", e.getMessage());
      return null;
    }
  }
}
