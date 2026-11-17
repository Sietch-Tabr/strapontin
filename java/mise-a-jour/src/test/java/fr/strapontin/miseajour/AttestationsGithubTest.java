/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link AttestationsGithub}, face à un faux GitHub.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class AttestationsGithubTest {

  private static final String EMPREINTE = "a".repeat(64);
  private static final String CHEMIN = "/repos/proprio/depot/attestations/sha256:" + EMPREINTE;

  private HttpServer serveur;
  private String base;

  @BeforeEach
  void demarrer() throws IOException {
    serveur = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    serveur.start();
    base = "http://localhost:" + serveur.getAddress().getPort();
  }

  @AfterEach
  void arreter() {
    serveur.stop(0);
  }

  private void repondre(final int statut, final String corps) {
    serveur.createContext(
        CHEMIN,
        echange -> {
          final byte[] octets = corps.getBytes(StandardCharsets.UTF_8);
          echange.sendResponseHeaders(statut, octets.length == 0 ? -1 : octets.length);
          if (octets.length > 0) {
            echange.getResponseBody().write(octets);
          }
          echange.close();
        });
  }

  private AttestationsGithub source() {
    final ConfigurationSource configuration =
        new ConfigurationSource(
            URI.create(base), "proprio/depot", Set.of("localhost"), false, 1000, "essai/1");
    return new AttestationsGithub(SourceGithub.clientSansRedirection(), configuration);
  }

  @Test
  void bundles_reponseValide_retourneChaqueBundleEnJson() {
    repondre(200, "{\"attestations\":[{\"bundle\":{\"a\":1}},{\"bundle\":{\"b\":2}}]}");
    assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), source().bundles("proprio/depot", EMPREINTE));
  }

  @Test
  void bundles_attestationSansBundle_estIgnoree() {
    repondre(200, "{\"attestations\":[{\"repository_id\":1},{\"bundle\":null},{\"bundle\":{}}]}");
    assertEquals(List.of("{}"), source().bundles("proprio/depot", EMPREINTE));
  }

  @Test
  void bundles_empreinteInconnue_retourneUneListeVide() {
    repondre(404, "{}");
    assertTrue(source().bundles("proprio/depot", EMPREINTE).isEmpty());
  }

  @Test
  void bundles_erreurDuServeur_leveUneException() {
    repondre(500, "{}");
    assertThrows(
        SourceInaccessibleException.class, () -> source().bundles("proprio/depot", EMPREINTE));
  }

  @Test
  void bundles_corpsIllisible_leveUneException() {
    repondre(200, "pas du json {");
    assertThrows(
        SourceInaccessibleException.class, () -> source().bundles("proprio/depot", EMPREINTE));
  }
}
