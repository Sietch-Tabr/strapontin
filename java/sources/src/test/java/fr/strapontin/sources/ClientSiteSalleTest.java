/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Tests de {@link ClientSiteSalle} : robot déclaré, robots.txt respecté, refus du site propagés.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class ClientSiteSalleTest {

  private MockRestServiceServer serveur;

  private ClientSiteSalle client() {
    final RestClient.Builder builder =
        RestClient.builder().defaultHeader("User-Agent", ClientSiteSalle.USER_AGENT);
    serveur = MockRestServiceServer.bindTo(builder).build();
    return new ClientSiteSalle(builder.build(), Duration.ZERO);
  }

  @Test
  void lire_pageAutorisee_lisLeRobotsTxtPuisLaPageAvecLeUserAgentDuRobot() {
    final ClientSiteSalle client = client();
    serveur
        .expect(requestTo("https://exemple.fr/robots.txt"))
        .andExpect(header("User-Agent", ClientSiteSalle.USER_AGENT))
        .andRespond(withSuccess("User-agent: *\nDisallow: /admin\n", MediaType.TEXT_PLAIN));
    serveur
        .expect(requestTo("https://exemple.fr/agenda"))
        .andExpect(header("User-Agent", ClientSiteSalle.USER_AGENT))
        .andRespond(withSuccess("<html>agenda</html>", MediaType.TEXT_HTML));

    assertEquals("<html>agenda</html>", client.lire("https://exemple.fr/agenda"));
    serveur.verify();
  }

  @Test
  void lire_pageInterditeParRobotsTxt_leveUneExceptionSansLaRequerir() {
    final ClientSiteSalle client = client();
    serveur
        .expect(requestTo("https://exemple.fr/robots.txt"))
        .andRespond(withSuccess("User-agent: *\nDisallow: /agenda\n", MediaType.TEXT_PLAIN));

    assertThrows(IllegalStateException.class, () -> client.lire("https://exemple.fr/agenda"));
    serveur.verify();
  }

  @Test
  void lire_robotsTxtAbsent_aucuneRestriction() {
    final ClientSiteSalle client = client();
    serveur
        .expect(requestTo("https://exemple.fr/robots.txt"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));
    serveur
        .expect(requestTo("https://exemple.fr/agenda"))
        .andRespond(withSuccess("ok", MediaType.TEXT_HTML));

    assertEquals("ok", client.lire("https://exemple.fr/agenda"));
  }

  @Test
  void lire_robotsTxtDeLHote_n_estLuQuUneFoisPourPlusieursPages() {
    final ClientSiteSalle client = client();
    serveur
        .expect(requestTo("https://exemple.fr/robots.txt"))
        .andRespond(withSuccess("", MediaType.TEXT_PLAIN));
    serveur
        .expect(requestTo("https://exemple.fr/a"))
        .andRespond(withSuccess("A", MediaType.TEXT_HTML));
    serveur
        .expect(requestTo("https://exemple.fr/b"))
        .andRespond(withSuccess("B", MediaType.TEXT_HTML));

    assertEquals("A", client.lire("https://exemple.fr/a"));
    assertEquals("B", client.lire("https://exemple.fr/b"));
    serveur.verify();
  }

  @Test
  void lire_siteQuiLimiteLeDebit_propageLeRefusSansInsister() {
    final ClientSiteSalle client = client();
    serveur
        .expect(requestTo("https://exemple.fr/robots.txt"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    assertThrows(HttpClientErrorException.class, () -> client.lire("https://exemple.fr/agenda"));
  }
}
