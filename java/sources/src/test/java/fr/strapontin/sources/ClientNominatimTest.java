/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/// Tests unitaires pour {@link ClientNominatim}.
///
/// @since 2.9.0
/// @author Kwaite
class ClientNominatimTest {

  private static ClientNominatim source(RestClient restClient, CadenceurAppels cadenceur) {
    return new ClientNominatim(restClient, cadenceur);
  }

  @Test
  void geocoder_resultatValide_extraitLesCoordonnees() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();
    CadenceurAppels cadenceur = mock(CadenceurAppels.class);

    serveur
        .expect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("nominatim.openstreetmap.org")))
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                """
                [{"lat":"48.8566","lon":"2.3522","display_name":"Paris"}]
                """,
                MediaType.APPLICATION_JSON));

    Optional<ClientNominatim.Coordonnees> resultat =
        source(restClient, cadenceur).geocoder("Salle Test");

    assertTrue(resultat.isPresent());
    assertEquals(48.8566, resultat.get().latitude(), 0.0001);
    assertEquals(2.3522, resultat.get().longitude(), 0.0001);
    verify(cadenceur).attendre();
    serveur.verify();
  }

  @Test
  void geocoder_aucunResultat_retourneVide() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();
    CadenceurAppels cadenceur = mock(CadenceurAppels.class);

    serveur
        .expect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("nominatim.openstreetmap.org")))
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                "[]", MediaType.APPLICATION_JSON));

    Optional<ClientNominatim.Coordonnees> resultat =
        source(restClient, cadenceur).geocoder("Introuvable");

    assertTrue(resultat.isEmpty());
  }

  @Test
  void geocoder_erreurHttp_retourneVideSansPropager() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();
    CadenceurAppels cadenceur = mock(CadenceurAppels.class);

    serveur
        .expect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("nominatim.openstreetmap.org")))
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators
                .withServerError());

    Optional<ClientNominatim.Coordonnees> resultat =
        source(restClient, cadenceur).geocoder("Erreur");

    assertTrue(resultat.isEmpty());
  }
}
