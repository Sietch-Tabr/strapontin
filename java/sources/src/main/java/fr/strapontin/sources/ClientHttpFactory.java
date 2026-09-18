/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Fabrique de {@link SimpleClientHttpRequestFactory} à timeouts
 * explicites, partagée entre les sources qui en ont besoin ({@link
 * DigitickSource}, {@link OverpassSalleSource}, {@link
 * ClientSparqlWikidata}).
 * @since 2.7.0
 * @author Kwaite
 */
final class ClientHttpFactory {

  private ClientHttpFactory() {}

  /**
   * Crée une fabrique de requêtes HTTP avec timeouts explicites.
   * @param connexion durée maximale d'établissement de la connexion
   * @param lecture durée maximale d'attente d'une réponse (connexion établie)
   * @return fabrique configurée
   */
  /* package-private : appelé par les clients HTTP du même package. */
  static SimpleClientHttpRequestFactory creerRequestFactory(
      final Duration connexion, final Duration lecture) {
    final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connexion);
    factory.setReadTimeout(lecture);
    return factory;
  }
}
