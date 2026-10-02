/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link WebConfiguration} : le jeton de l'API interne du worker n'est joint
 * qu'aux requêtes qui lui sont destinées.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class WebConfigurationTest {

  @Test
  void estApiInterneWorker_workerLocal_vrai() {
    assertTrue(
        WebConfiguration.estApiInterneWorker(URI.create("http://localhost:8081/trigger/tout")));
    assertTrue(
        WebConfiguration.estApiInterneWorker(URI.create("http://127.0.0.1:8081/trigger/job")));
  }

  @Test
  void estApiInterneWorker_hoteExterneOuAutrePort_faux() {
    assertFalse(
        WebConfiguration.estApiInterneWorker(
            URI.create("https://app.ticketmaster.com/discovery/v2/events.json")));
    assertFalse(WebConfiguration.estApiInterneWorker(URI.create("http://localhost:9091/actuator")));
    assertFalse(
        WebConfiguration.estApiInterneWorker(URI.create("http://localhost.attaquant.fr:8081/x")));
  }
}
