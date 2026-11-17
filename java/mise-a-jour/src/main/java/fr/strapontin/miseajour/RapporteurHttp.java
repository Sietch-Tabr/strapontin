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
  private static final Duration PAUSE = Duration.ofSeconds(3);
  private static final int TENTATIVES = 30;
  private static final int STATUT_OK = 200;
  private static final int STATUT_SERVEUR = 500;

  private final HttpClient client;
  private final URI adresse;
  private final String secret;
  private final int tentatives;
  private final Duration pause;

  /**
   * L'application redémarre pendant ou juste après une mise à jour (retour
   * arrière compris) : l'envoi est repris pendant environ une minute et demie.
   *
   * @param client client HTTP
   * @param adresse adresse de {@code /api/interne/mise-a-jour-statut}
   * @param secret valeur de {@code UPDATE_INTERNAL_SECRET}, vide si absente
   */
  public RapporteurHttp(final HttpClient client, final URI adresse, final String secret) {
    this(client, adresse, secret, TENTATIVES, PAUSE);
  }

  /* package-private : les tests réduisent le nombre d'essais et la pause. */
  RapporteurHttp(
      final HttpClient client,
      final URI adresse,
      final String secret,
      final int tentatives,
      final Duration pause) {
    this.client = client;
    this.adresse = adresse;
    this.secret = secret;
    this.tentatives = tentatives;
    this.pause = pause;
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
    for (int essai = 1; essai <= tentatives; essai++) {
      if (tenter(requete)) {
        return;
      }
      if (essai < tentatives && pauseInterrompue()) {
        return;
      }
    }
    JOURNAL.log(System.Logger.Level.WARNING, "Rapport non envoyé après {0} tentatives", tentatives);
  }

  /**
   * Un essai d'envoi.
   *
   * @return vrai si la réponse est définitive, faux s'il faut reprendre
   */
  private boolean tenter(final HttpRequest requete) {
    try {
      final HttpResponse<Void> reponse =
          client.send(requete, HttpResponse.BodyHandlers.discarding());
      if (reponse.statusCode() >= STATUT_SERVEUR) {
        return false;
      }
      if (reponse.statusCode() != STATUT_OK) {
        JOURNAL.log(System.Logger.Level.WARNING, "Rapport refusé : HTTP {0}", reponse.statusCode());
      }
      return true;
    } catch (final IOException e) {
      JOURNAL.log(System.Logger.Level.DEBUG, "Application injoignable, nouvel essai", e);
      return false;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      JOURNAL.log(System.Logger.Level.WARNING, "Rapport interrompu", e);
      return true;
    }
  }

  /**
   * Pause entre deux essais.
   *
   * @return vrai si la pause a été interrompue : il faut s'arrêter
   */
  private boolean pauseInterrompue() {
    try {
      Thread.sleep(pause);
      return false;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return true;
    }
  }

  private static String encoder(final String valeur) {
    return URLEncoder.encode(valeur, StandardCharsets.UTF_8);
  }
}
