/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.sources.ResolveurNomCanonique.ResultatResolution;
import fr.strapontin.sources.ResolveurNomCanonique.SourceResolution;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResolveurNomCanoniqueTest {

  @Test
  void constructeurAvecEmail_initialise_sansErreur() {
    assertDoesNotThrow(() -> {
      new ResolveurNomCanonique("test@example.com");
    });
  }

  @Test
  void resultatResolution_getters_retournentValeursCorrectes() {
    var resultat = new ResultatResolution("Daft Punk", SourceResolution.DEEZER);
    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
  }

  @Test
  void resoudreNomCanonique_resultatEnCache_retourneSansHttp() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    Cache<String, ResultatResolution> cache = Caffeine.newBuilder().build();
    var attendu = new ResultatResolution("Daft Punk", SourceResolution.MUSICBRAINZ);
    cache.put("artiste:daft punk", attendu);

    ResolveurNomCanonique resolveur = new ResolveurNomCanonique(restClient, cache);
    ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "daft punk");

    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.MUSICBRAINZ, resultat.source());
    serveur.verify();
  }
}
