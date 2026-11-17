/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/**
 * Requêtes GET vers GitHub dont chaque adresse, redirections comprises,
 * est contrôlée : les redirections ne sont jamais suivies par le client.
 *
 * @since 3.2.0
 * @author Kwaite
 */
final class ClientControle {

  private static final int REDIRECTIONS_MAX = 5;
  private static final int STATUT_REDIRECTION_MIN = 300;
  private static final int STATUT_REDIRECTION_MAX = 399;
  private static final Duration DELAI_REQUETE = Duration.ofMinutes(10);

  private final HttpClient client;
  private final ConfigurationSource configuration;
  private final AdressesAutorisees controleAdresses;

  /**
   * @param client client HTTP sans suivi automatique des redirections
   * @param configuration réglages de la source
   */
  /* package-private : composé par les sources de releases et d'attestations. */
  ClientControle(final HttpClient client, final ConfigurationSource configuration) {
    this.client = client;
    this.configuration = configuration;
    this.controleAdresses =
        new AdressesAutorisees(configuration.hotesAutorises(), configuration.httpsExige());
  }

  /**
   * Adresses autorisées, pour contrôler aussi les adresses lues dans une réponse.
   *
   * @return le contrôle d'adresses de cette source
   */
  /* package-private : composé par les sources de releases et d'attestations. */
  AdressesAutorisees adresses() {
    return controleAdresses;
  }

  /**
   * Interroge une adresse en suivant, après contrôle, ses redirections.
   *
   * @param depart première adresse
   * @param accept type de contenu attendu
   * @return la réponse définitive, dont le flux est à fermer
   */
  /* package-private : composé par les sources de releases et d'attestations. */
  Flux demander(final URI depart, final String accept) {
    URI courante = depart;
    for (int saut = 0; saut <= REDIRECTIONS_MAX; saut++) {
      controleAdresses.verifier(courante);
      final HttpResponse<InputStream> reponse = envoyer(courante, accept);
      if (reponse.statusCode() < STATUT_REDIRECTION_MIN
          || reponse.statusCode() > STATUT_REDIRECTION_MAX) {
        return new Flux(reponse.statusCode(), reponse.body());
      }
      final String emplacement = reponse.headers().firstValue("Location").orElse(null);
      final InputStream corps = reponse.body();
      try (corps) {
        if (emplacement == null) {
          throw new SourceInaccessibleException("Redirection sans adresse");
        }
        courante = courante.resolve(emplacement);
      } catch (final IOException e) {
        throw new SourceInaccessibleException("Redirection illisible", e);
      }
    }
    throw new SourceInaccessibleException("Trop de redirections depuis " + depart);
  }

  private HttpResponse<InputStream> envoyer(final URI adresse, final String accept) {
    final HttpRequest requete =
        HttpRequest.newBuilder(adresse)
            .timeout(DELAI_REQUETE)
            .header("User-Agent", configuration.agent())
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build();
    try {
      return client.send(requete, BodyHandlers.ofInputStream());
    } catch (final IOException e) {
      throw new SourceInaccessibleException("Source injoignable : " + adresse.getHost(), e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceInaccessibleException("Requête interrompue", e);
    }
  }

  /**
   * Réponse définitive (hors redirection) : code HTTP et flux à fermer.
   */
  /* package-private : valeur de retour interne de ce client. */
  record Flux(int statut, InputStream contenu) implements AutoCloseable {

    @Override
    public void close() throws IOException {
      contenu.close();
    }
  }
}
