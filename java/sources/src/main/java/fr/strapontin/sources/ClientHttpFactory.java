/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/// Fabrique de {@link SimpleClientHttpRequestFactory} à timeouts explicites (#887), partagée
/// entre les sources qui en ont besoin pour éviter de dupliquer une 4e fois le pattern déjà
/// présent dans {@link DigitickSource}, {@link OverpassSalleSource} et {@link
/// ClientSparqlWikidata}.
///
/// @since 2.7.0
/// @author Kwaite
final class ClientHttpFactory {

  private ClientHttpFactory() {}

  /// Crée une fabrique de requêtes HTTP avec timeouts de connexion et de lecture explicites.
  ///
  /// @param connexion durée maximale d'établissement de la connexion
  /// @param lecture   durée maximale d'attente d'une réponse une fois la connexion établie
  /// @return fabrique configurée
  static SimpleClientHttpRequestFactory creerRequestFactory(Duration connexion, Duration lecture) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connexion);
    factory.setReadTimeout(lecture);
    return factory;
  }
}
