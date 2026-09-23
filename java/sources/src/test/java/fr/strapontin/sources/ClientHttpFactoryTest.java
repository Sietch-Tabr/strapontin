/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Tests unitaires pour {@link ClientHttpFactory}.
 *
 * @since 2.7.0
 * @author Kwaite
 */
class ClientHttpFactoryTest {

  @Test
  void creerRequestFactory_avecDurees_retourneFabriqueNonNulle() {
    final JdkClientHttpRequestFactory factory =
        ClientHttpFactory.creerRequestFactory(Duration.ofSeconds(10), Duration.ofSeconds(30));

    assertNotNull(factory);
  }
}
