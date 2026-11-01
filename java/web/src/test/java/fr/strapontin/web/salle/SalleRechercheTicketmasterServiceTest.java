/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.http.CadenceurAppels;
import fr.strapontin.web.config.ConfigService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires pour {@link SalleRechercheTicketmasterService}.
 *
 * @since 2.9.1
 * @author Kwaite
 */
class SalleRechercheTicketmasterServiceTest {

  // Ticketmaster est aussi appelé directement par le worker (processus indépendant) : sans passer
  // par le cadenceur partagé avant l'appel HTTP, les deux processus ne peuvent pas coordonner leur
  // rythme d'appel et le rate-limit TM (Spike Arrest) reste exposé.
  @Test
  void rechercher_cleConfiguree_attendLeCadenceurAvantLAppelHttp() {
    final ConfigService configService = mock(ConfigService.class);
    when(configService.lireConfig("TICKETMASTER_API_KEY")).thenReturn(Optional.of("fake-key"));
    final CadenceurAppels cadenceur = mock(CadenceurAppels.class);

    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();
    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("app.ticketmaster.com")))
        .andRespond(
            MockRestResponseCreators.withSuccess(
                "{\"_embedded\":{\"venues\":[]}}", MediaType.APPLICATION_JSON));

    final SalleRechercheTicketmasterService service =
        new SalleRechercheTicketmasterService(
            configService, restClient, new ObjectMapper(), cadenceur);

    service.rechercher("Bataclan");

    verify(cadenceur).attendre();
    serveur.verify();
  }

  @Test
  void rechercher_accentVirguleEtCleSpeciale_encodeUneSeuleFois() {
    final ConfigService configService = mock(ConfigService.class);
    when(configService.lireConfig("TICKETMASTER_API_KEY")).thenReturn(Optional.of("cle+a/b"));

    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();
    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("keyword=Th%C3%A9%C3%A2tre%2C%20Paris&"),
                    org.hamcrest.Matchers.containsString("apikey=cle%2Ba%2Fb"))))
        .andRespond(
            MockRestResponseCreators.withSuccess(
                "{\"_embedded\":{\"venues\":[]}}", MediaType.APPLICATION_JSON));

    new SalleRechercheTicketmasterService(
            configService, restClient, new ObjectMapper(), mock(CadenceurAppels.class))
        .rechercher("Théâtre, Paris");

    serveur.verify();
  }
}
