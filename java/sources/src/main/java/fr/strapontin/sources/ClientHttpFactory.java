/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Fabrique de {@link JdkClientHttpRequestFactory} à timeouts explicites
 * (HTTP/3 dès qu'une API tierce le supportera), partagée entre les
 * sources qui en ont besoin ({@link EventimSource}, {@link
 * OverpassSalleSource}, {@link ClientSparqlWikidata}).
 * @since 2.7.0
 * @author Kwaite
 */
final class ClientHttpFactory {

  private ClientHttpFactory() {}

  /**
   * Crée une fabrique de requêtes HTTP avec timeouts explicites — le
   * timeout de connexion se configure sur le {@link HttpClient} sous-jacent,
   * {@link JdkClientHttpRequestFactory} n'expose que le timeout de lecture.
   * @param connexion durée maximale d'établissement de la connexion
   * @param lecture durée maximale d'attente d'une réponse (connexion établie)
   * @return fabrique configurée
   */
  // HttpClient jamais fermé : partagé, pool de connexions réutilisé, pas un
  // usage ponctuel.
  @SuppressWarnings("PMD.CloseResource")
  /* package-private : appelé par les clients HTTP du même package. */
  static JdkClientHttpRequestFactory creerRequestFactory(
      final Duration connexion, final Duration lecture) {
    final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connexion).build();
    final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(lecture);
    return factory;
  }
}
