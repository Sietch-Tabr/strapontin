/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.webhooks.dto.WebhookDTO;
import com.resend.services.webhooks.model.ListWebhookEventsResponseSuccess;
import com.resend.services.webhooks.model.ListWebhooksResponseSuccess;
import fr.strapontin.web.config.ConfigService;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Liste et rejoue les événements du webhook Resend configuré ({@code RESEND_API_KEY}) — capacité admin
 * exposée par le SDK resend-java 4.23.0 ({@code Webhooks.listEvents}/{@code replayEvent}).
 *
 * <p>Le {@code webhookId} n'est jamais configuré explicitement : résolu à chaque appel en filtrant
 * {@code webhooks().list()} sur l'endpoint déjà connu ({@code PUBLIC_BASE_URL +
 * "/api/webhooks/resend"}) plutôt que d'ajouter une clé {@code app_config} de plus à maintenir en
 * cohérence avec le dashboard Resend.
 *
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class ResendWebhooksAdminService {

  private static final Logger log = LoggerFactory.getLogger(ResendWebhooksAdminService.class);

  private final ConfigService configService;
  private final Function<String, Resend> fabriqueResend;

  @Autowired
  public ResendWebhooksAdminService(final ConfigService configService) {
    this(configService, Resend::new);
  }

  /**
   * Constructeur avec fabrique de client injectable — le SDK resend-java ne fournit aucun point
   * d'injection HTTP public (contrairement à {@code RestClient} pour les autres sources), la
   * fabrique est le seul moyen de substituer un client {@link Resend} mocké en test.
   */
  ResendWebhooksAdminService(final ConfigService configService, final Function<String, Resend> fabriqueResend) {
    this.configService = configService;
    this.fabriqueResend = fabriqueResend;
  }

  /**
   * Un événement webhook Resend, tel qu'affiché dans l'admin.
   *
   * @param id identifiant de l'événement (svix-id)
   * @param type type d'événement (ex. {@code "email.bounced"})
   * @param statut statut de livraison ({@code pending}/{@code attempting}/{@code success}/{@code
   *   failed}), ou {@code null} si inconnu
   * @param creeLe horodatage de création (format ISO renvoyé par l'API Resend)
   */
  public record EvenementWebhook(String id, String type, String statut, String creeLe) {}

  /**
   * Liste les événements récents du webhook configuré.
   *
   * @return la liste des événements, vide si {@code RESEND_API_KEY}/{@code PUBLIC_BASE_URL} absent, aucun
   *   webhook ne correspond, ou en cas d'erreur API (journalisée, jamais propagée)
   */
  public List<EvenementWebhook> listerEvenements() {
    final Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
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
      final List<EvenementWebhook> evenements =
          reponse.getData() != null
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
      return evenements;
    } catch (ResendException e) {
      log.warn("Erreur listing événements webhook Resend : {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * Rejoue un événement webhook en échec (statut {@code failed}).
   *
   * @param eventId identifiant de l'événement à rejouer
   * @return {@code true} si le rejeu a été accepté par Resend, {@code false} sinon (configuration
   *   absente, webhook introuvable, erreur API)
   */
  public boolean rejouerEvenement(final String eventId) {
    final Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
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
      log.info("rejeu événement webhook Resend id={}", eventId);
      return true;
    } catch (ResendException e) {
      log.warn("Erreur rejeu événement webhook Resend id={} : {}", eventId, e.getMessage());
      return false;
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
      log.warn("Erreur résolution du webhookId Resend : {}", e.getMessage());
      return null;
    }
  }
}
