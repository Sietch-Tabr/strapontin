/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.enrichissement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.sources.http.CadenceurAppels;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * Tests unitaires pour {@link ClientNominatim}.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class ClientNominatimTest {

  private static ClientNominatim source(
      final RestClient restClient, final CadenceurAppels cadenceur) {
    return new ClientNominatim(restClient, cadenceur);
  }

  @Test
  void geocoder_resultatValide_extraitLesCoordonnees() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();
    final CadenceurAppels cadenceur = mock(CadenceurAppels.class);

    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("nominatim.openstreetmap.org")))
        .andRespond(
            MockRestResponseCreators.withSuccess(
                """
                [{"lat":"48.8566","lon":"2.3522","display_name":"Paris"}]
                """,
                MediaType.APPLICATION_JSON));

    final Optional<ClientNominatim.Coordonnees> resultat =
        source(restClient, cadenceur).geocoder("Salle Test");

    assertTrue(resultat.isPresent());
    assertEquals(48.8566, resultat.get().latitude(), 0.0001);
    assertEquals(2.3522, resultat.get().longitude(), 0.0001);
    verify(cadenceur).attendre();
    serveur.verify();
  }

  @Test
  void geocoder_aucunResultat_retourneVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();
    final CadenceurAppels cadenceur = mock(CadenceurAppels.class);

    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("nominatim.openstreetmap.org")))
        .andRespond(MockRestResponseCreators.withSuccess("[]", MediaType.APPLICATION_JSON));

    final Optional<ClientNominatim.Coordonnees> resultat =
        source(restClient, cadenceur).geocoder("Introuvable");

    assertTrue(resultat.isEmpty());
  }

  @Test
  void geocoder_erreurHttp_retourneVideSansPropager() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();
    final CadenceurAppels cadenceur = mock(CadenceurAppels.class);

    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("nominatim.openstreetmap.org")))
        .andRespond(MockRestResponseCreators.withServerError());

    final Optional<ClientNominatim.Coordonnees> resultat =
        source(restClient, cadenceur).geocoder("Erreur");

    assertTrue(resultat.isEmpty());
  }

  @Test
  void geocoder_accentEtVirgule_encodeUneSeuleFois() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString(
                    "q=Th%C3%A9%C3%A2tre%20du%20Gymnase%2C%20Paris&")))
        .andRespond(MockRestResponseCreators.withSuccess("[]", MediaType.APPLICATION_JSON));

    source(restClient, mock(CadenceurAppels.class)).geocoder("Théâtre du Gymnase, Paris");

    serveur.verify();
  }

  @Test
  void rechercherLieux_accentEtVirgule_encodeUneSeuleFois() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("q=Le%20Kilowatt%2C%20Vitry%20%C3%A9&")))
        .andRespond(MockRestResponseCreators.withSuccess("[]", MediaType.APPLICATION_JSON));

    final ClientNominatim.ResultatRecherche resultat =
        source(restClient, mock(CadenceurAppels.class))
            .rechercherLieux("Le Kilowatt, Vitry é", new ClientNominatim.Coordonnees(48.78, 2.39));

    assertTrue(resultat.succes());
    serveur.verify();
  }

  // Le K : Nominatim donne le village de Tinqueux, et « municipality » désigne Reims,
  // l'agglomération.
  @Test
  void communeDe_positionDUnVillage_retourneLaCommuneEtNonLIntercommunalite() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final CadenceurAppels cadenceur = mock(CadenceurAppels.class);
    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("/reverse?lat=49.239700&lon=3.987500")))
        .andRespond(
            MockRestResponseCreators.withSuccess(
                """
                {"address":{"village":"Tinqueux","municipality":"Reims","postcode":"51430"}}
                """,
                MediaType.APPLICATION_JSON));

    final ClientNominatim.ResultatCommune resultat =
        source(builder.build(), cadenceur)
            .communeDe(new ClientNominatim.Coordonnees(49.2397, 3.9875));

    assertTrue(resultat.succes());
    assertEquals(Optional.of("Tinqueux"), resultat.commune());
    assertEquals(Optional.of("51430"), resultat.codePostal());
    verify(cadenceur).attendre();
    serveur.verify();
  }

  @Test
  void communeDe_erreurHttp_neConcluPas() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    serveur
        .expect(MockRestRequestMatchers.anything())
        .andRespond(MockRestResponseCreators.withServerError());

    final ClientNominatim.ResultatCommune resultat =
        source(builder.build(), mock(CadenceurAppels.class))
            .communeDe(new ClientNominatim.Coordonnees(49.2397, 3.9875));

    assertEquals(false, resultat.succes());
    assertTrue(resultat.commune().isEmpty());
  }
}
