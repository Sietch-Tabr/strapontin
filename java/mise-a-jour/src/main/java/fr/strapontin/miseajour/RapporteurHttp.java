/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rapport envoyé à l'endpoint interne de l'application par HTTP local,
 * protégé par un secret partagé. Sans secret, rien n'est envoyé.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class RapporteurHttp implements Rapporteur {

  private static final System.Logger JOURNAL = System.getLogger("miseajour");
  private static final Duration DELAI = Duration.ofSeconds(10);
  private static final int STATUT_OK = 200;

  private final HttpClient client;
  private final URI adresse;
  private final String secret;

  /**
   * @param client client HTTP
   * @param adresse adresse de {@code /api/interne/mise-a-jour-statut}
   * @param secret valeur de {@code UPDATE_INTERNAL_SECRET}, vide si absente
   */
  public RapporteurHttp(final HttpClient client, final URI adresse, final String secret) {
    this.client = client;
    this.adresse = adresse;
    this.secret = secret;
  }

  @Override
  public void envoyer(final boolean reussie, final String version, final String message) {
    if (secret == null || secret.isBlank()) {
      JOURNAL.log(
          System.Logger.Level.WARNING, "UPDATE_INTERNAL_SECRET absent : rapport non envoyé");
      return;
    }
    final String corps =
        Map.of("resultat", reussie ? "reussi" : "echec", "version", version, "message", message)
            .entrySet()
            .stream()
            .map(e -> encoder(e.getKey()) + "=" + encoder(e.getValue()))
            .collect(Collectors.joining("&"));
    final HttpRequest requete =
        HttpRequest.newBuilder(adresse)
            .timeout(DELAI)
            .header("Authorization", "Bearer " + secret)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(corps))
            .build();
    try {
      final HttpResponse<Void> reponse =
          client.send(requete, HttpResponse.BodyHandlers.discarding());
      if (reponse.statusCode() != STATUT_OK) {
        JOURNAL.log(System.Logger.Level.WARNING, "Rapport refusé : HTTP {0}", reponse.statusCode());
      }
    } catch (final IOException e) {
      JOURNAL.log(System.Logger.Level.WARNING, "Rapport non envoyé : application injoignable", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      JOURNAL.log(System.Logger.Level.WARNING, "Rapport interrompu", e);
    }
  }

  private static String encoder(final String valeur) {
    return URLEncoder.encode(valeur, StandardCharsets.UTF_8);
  }
}
