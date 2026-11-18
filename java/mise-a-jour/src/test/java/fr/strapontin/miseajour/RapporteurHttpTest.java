/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import fr.strapontin.exploitation.AppelInterne;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests de {@link RapporteurHttp} contre un serveur HTTP local.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class RapporteurHttpTest {

  private HttpServer serveur;
  private URI adresse;
  private final List<String> corps = new ArrayList<>();
  private final List<String> autorisations = new ArrayList<>();
  private final Deque<Integer> statuts = new ArrayDeque<>();

  @BeforeEach
  void demarrer() throws IOException {
    serveur = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    serveur.createContext(
        "/api/interne/mise-a-jour-statut",
        echange -> {
          corps.add(new String(echange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          autorisations.add(echange.getRequestHeaders().getFirst("Authorization"));
          echange.sendResponseHeaders(statuts.isEmpty() ? 200 : statuts.poll(), -1);
          echange.close();
        });
    serveur.start();
    adresse =
        URI.create(
            "http://127.0.0.1:"
                + serveur.getAddress().getPort()
                + "/api/interne/mise-a-jour-statut");
  }

  @AfterEach
  void arreter() {
    serveur.stop(0);
  }

  @Test
  void envoyer_succes_posteLeResultatAvecLeJeton() {
    try (HttpClient client = HttpClient.newHttpClient()) {
      new RapporteurHttp(client, adresse, "secret").envoyer(true, "3.2.0", "Version installée.");
    }

    assertEquals(1, corps.size());
    final String decode = URLDecoder.decode(corps.get(0), StandardCharsets.UTF_8);
    assertAll(
        () -> assertEquals("Bearer secret", autorisations.get(0)),
        () -> assertTrue(decode.contains("resultat=reussi")),
        () -> assertTrue(decode.contains("version=3.2.0")),
        () -> assertTrue(decode.contains("message=Version installée.")));
  }

  @Test
  void envoyer_echec_posteLeResultatEchec() {
    try (HttpClient client = HttpClient.newHttpClient()) {
      new RapporteurHttp(client, adresse, "secret").envoyer(false, "3.2.0", "a&b=c");
    }

    assertTrue(corps.get(0).contains("resultat=echec"));
    assertTrue(URLDecoder.decode(corps.get(0), StandardCharsets.UTF_8).contains("message=a&b=c"));
  }

  @Test
  void envoyer_sansSecret_neContactePasLApplication() {
    try (HttpClient client = HttpClient.newHttpClient()) {
      new RapporteurHttp(client, adresse, "").envoyer(true, "3.2.0", "ok");
    }

    assertTrue(corps.isEmpty());
  }

  @Test
  void envoyer_applicationRefuse_neLevePas() {
    statuts.add(403);
    try (HttpClient client = HttpClient.newHttpClient()) {
      assertDoesNotThrow(
          () -> new RapporteurHttp(client, adresse, "secret").envoyer(true, "3.2.0", "ok"));
    }
  }

  @Test
  void envoyer_applicationInjoignable_neLevePas() {
    serveur.stop(0);
    try (HttpClient client = HttpClient.newHttpClient()) {
      assertDoesNotThrow(
          () ->
              new RapporteurHttp(new AppelInterne(client, 2, Duration.ZERO), adresse, "secret")
                  .envoyer(false, "3.2.0", "x"));
    }
  }
}
