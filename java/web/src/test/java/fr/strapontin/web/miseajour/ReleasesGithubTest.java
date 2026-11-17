/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.exploitation.Version;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests unitaires pour {@link ReleasesGithub}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class ReleasesGithubTest {

  private static final String ASSETS =
      "[{\"name\":\"web.jar\"},{\"name\":\"worker.jar\"},{\"name\":\"mise-a-jour.jar\"},"
          + "{\"name\":\"exploitation.tar.gz\"},{\"name\":\"SHA256SUMS\"}]";

  /** Horloge réglable pour vérifier la durée de vie du cache. */
  private static final class HorlogeMobile extends Clock {
    private Instant instant = Instant.parse("2026-11-17T10:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  private static String release(
      final String etiquette,
      final boolean brouillon,
      final boolean preRelease,
      final String assets) {
    return "{\"tag_name\":\""
        + etiquette
        + "\",\"draft\":"
        + brouillon
        + ",\"prerelease\":"
        + preRelease
        + ",\"assets\":"
        + assets
        + "}";
  }

  private static ReleasesGithub releases(
      final MockRestServiceServer[] serveur, final boolean preVersions, final Clock horloge) {
    final RestClient.Builder builder = RestClient.builder();
    serveur[0] = MockRestServiceServer.bindTo(builder).build();
    return new ReleasesGithub(
        builder.build(), new JsonMapper(), preVersions, "Sietch-Tabr/strapontin", horloge);
  }

  private static void repondre(final MockRestServiceServer serveur, final String json) {
    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                "https://api.github.com/repos/Sietch-Tabr/strapontin/releases?per_page=20"))
        .andRespond(MockRestResponseCreators.withSuccess(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void plusRecente_plusieursReleases_retourneLaPlusRecenteSuperieureALInstallee() {
    final MockRestServiceServer[] serveur = new MockRestServiceServer[1];
    final ReleasesGithub releases = releases(serveur, false, new HorlogeMobile());
    repondre(
        serveur[0],
        "["
            + release("v3.2.0", false, false, ASSETS)
            + ","
            + release("v3.1.6", false, false, ASSETS)
            + ","
            + release("v3.1.5", false, false, ASSETS)
            + "]");

    assertEquals(
        Optional.of(Version.analyser("3.2.0")), releases.plusRecente(Version.analyser("3.1.5")));
  }

  @Test
  void plusRecente_dejaAJour_retourneVide() {
    final MockRestServiceServer[] serveur = new MockRestServiceServer[1];
    final ReleasesGithub releases = releases(serveur, false, new HorlogeMobile());
    repondre(serveur[0], "[" + release("v3.1.5", false, false, ASSETS) + "]");

    assertTrue(releases.plusRecente(Version.analyser("3.1.5")).isEmpty());
  }

  @Test
  void plusRecente_brouillonEtReleaseIncomplete_sontIgnores() {
    final MockRestServiceServer[] serveur = new MockRestServiceServer[1];
    final ReleasesGithub releases = releases(serveur, false, new HorlogeMobile());
    repondre(
        serveur[0],
        "["
            + release("v3.3.0", true, false, ASSETS)
            + ","
            + release("v3.2.0", false, false, "[{\"name\":\"web.jar\"}]")
            + "]");

    assertTrue(releases.plusRecente(Version.analyser("3.1.5")).isEmpty());
  }

  @Test
  void plusRecente_preVersionSurProd_estIgnoree() {
    final MockRestServiceServer[] serveur = new MockRestServiceServer[1];
    final ReleasesGithub releases = releases(serveur, false, new HorlogeMobile());
    repondre(serveur[0], "[" + release("v3.2.0-dev.1", false, true, ASSETS) + "]");

    assertTrue(releases.plusRecente(Version.analyser("3.1.5")).isEmpty());
  }

  @Test
  void plusRecente_preVersionSurDev_estProposee() {
    final MockRestServiceServer[] serveur = new MockRestServiceServer[1];
    final ReleasesGithub releases = releases(serveur, true, new HorlogeMobile());
    repondre(serveur[0], "[" + release("v3.2.0-dev.1", false, true, ASSETS) + "]");

    assertEquals(
        Optional.of(Version.analyser("3.2.0-dev.1")),
        releases.plusRecente(Version.analyser("3.1.5")));
  }

  @Test
  void plusRecente_etiquetteInvalide_estIgnoree() {
    final MockRestServiceServer[] serveur = new MockRestServiceServer[1];
    final ReleasesGithub releases = releases(serveur, false, new HorlogeMobile());
    repondre(serveur[0], "[" + release("nightly", false, false, ASSETS) + "]");

    assertTrue(releases.plusRecente(Version.analyser("3.1.5")).isEmpty());
  }

  @Test
  void plusRecente_deuxAppelsRapproches_neLitGithubQuUneFois() {
    final MockRestServiceServer[] serveur = new MockRestServiceServer[1];
    final HorlogeMobile horloge = new HorlogeMobile();
    final ReleasesGithub releases = releases(serveur, false, horloge);
    repondre(serveur[0], "[" + release("v3.2.0", false, false, ASSETS) + "]");

    releases.plusRecente(Version.analyser("3.1.5"));
    horloge.instant = horloge.instant.plus(Duration.ofMinutes(5));
    releases.plusRecente(Version.analyser("3.1.5"));

    serveur[0].verify();
  }

  @Test
  void plusRecente_cachePerime_relitGithub() {
    final MockRestServiceServer[] serveur = new MockRestServiceServer[1];
    final HorlogeMobile horloge = new HorlogeMobile();
    final ReleasesGithub releases = releases(serveur, false, horloge);
    serveur[0]
        .expect(ExpectedCount.twice(), MockRestRequestMatchers.anything())
        .andRespond(
            MockRestResponseCreators.withSuccess(
                "[" + release("v3.2.0", false, false, ASSETS) + "]", MediaType.APPLICATION_JSON));

    releases.plusRecente(Version.analyser("3.1.5"));
    horloge.instant = horloge.instant.plus(Duration.ofMinutes(11));
    releases.plusRecente(Version.analyser("3.1.5"));

    serveur[0].verify();
  }

  @Test
  void plusRecente_githubEnErreur_retourneVideSansPropager() {
    final MockRestServiceServer[] serveur = new MockRestServiceServer[1];
    final ReleasesGithub releases = releases(serveur, false, new HorlogeMobile());
    serveur[0]
        .expect(MockRestRequestMatchers.anything())
        .andRespond(MockRestResponseCreators.withServerError());

    assertTrue(releases.plusRecente(Version.analyser("3.1.5")).isEmpty());
  }

  @Test
  void plusRecente_erreurApresUneLectureReussie_gardeLeDernierResultat() {
    final MockRestServiceServer[] serveur = new MockRestServiceServer[1];
    final HorlogeMobile horloge = new HorlogeMobile();
    final ReleasesGithub releases = releases(serveur, false, horloge);
    repondre(serveur[0], "[" + release("v3.2.0", false, false, ASSETS) + "]");
    serveur[0]
        .expect(MockRestRequestMatchers.anything())
        .andRespond(MockRestResponseCreators.withServerError());

    releases.plusRecente(Version.analyser("3.1.5"));
    horloge.instant = horloge.instant.plus(Duration.ofMinutes(11));

    assertEquals(
        Optional.of(Version.analyser("3.2.0")), releases.plusRecente(Version.analyser("3.1.5")));
  }
}
