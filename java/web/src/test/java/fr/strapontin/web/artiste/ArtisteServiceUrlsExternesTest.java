/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import static org.mockito.Mockito.mock;

import fr.strapontin.sources.http.IdentiteHttp;
import fr.strapontin.web.BudgetRecherchesExternes;
import fr.strapontin.web.admin.LogAdminService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Vérifie que les recherches d'artistes adressées à Wikidata et Deezer encodent le texte une seule
 * fois dans l'adresse réellement envoyée.
 */
class ArtisteServiceUrlsExternesTest {

  private static final IdentiteHttp IDENTITE =
      new IdentiteHttp("test", "https://exemple.test/projet", () -> "");

  private MockRestServiceServer serveur;
  private ArtisteService service;

  @BeforeEach
  void setUp() {
    final RestClient.Builder builder = RestClient.builder();
    serveur = MockRestServiceServer.bindTo(builder).build();
    final LogAdminService logAdminService = mock(LogAdminService.class);
    service =
        new ArtisteService(
            mock(ArtisteRepository.class),
            new ObjectMapper(),
            builder.build(),
            logAdminService,
            new BudgetRecherchesExternes(logAdminService),
            IDENTITE);
  }

  @Test
  void rechercherWikidata_accentEtVirgule_encodeUneSeuleFois() {
    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                Matchers.containsString("search=Myl%C3%A8ne%2C%20Farmer&")))
        .andRespond(
            MockRestResponseCreators.withSuccess("{\"search\":[]}", MediaType.APPLICATION_JSON));

    service.rechercherWikidata("Mylène, Farmer");

    serveur.verify();
  }

  @Test
  void rechercherDeezer_accent_encodeUneSeuleFois() {
    serveur
        .expect(MockRestRequestMatchers.requestTo(Matchers.containsString("q=Myl%C3%A8ne&")))
        .andRespond(
            MockRestResponseCreators.withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

    service.rechercherDeezer("Mylène");

    serveur.verify();
  }

  @Test
  void resoudreDeezerId_identifiantWikidata_estTransmisTelQuel() {
    serveur
        .expect(MockRestRequestMatchers.requestTo(Matchers.containsString("ids=Q16676017&")))
        .andRespond(
            MockRestResponseCreators.withSuccess("{\"entities\":{}}", MediaType.APPLICATION_JSON));

    service.resoudreDeezerId("Q16676017");

    serveur.verify();
  }
}
