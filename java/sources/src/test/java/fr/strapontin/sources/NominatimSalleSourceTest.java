/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link NominatimSalleSource}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class NominatimSalleSourceTest {

  private static final UUID ID = UUID.randomUUID();
  private static final SalleAEnrichir LA_CIGALE =
      new SalleAEnrichir(ID, "La Cigale", "la cigale", "Paris", "75", null, 48.88220, 2.34040);

  private ClientNominatim client;
  private NominatimSalleSource source;

  @BeforeEach
  void setUp() {
    client = mock(ClientNominatim.class);
    source = new NominatimSalleSource(client);
  }

  private static ClientNominatim.LieuOsm lieu(
      final String nom, final double lat, final double lon, final String wikidata) {
    return new ClientNominatim.LieuOsm(
        nom,
        new ClientNominatim.Coordonnees(lat, lon),
        Map.of("wikidata", wikidata, "website", "https://www.lacigale.fr"));
  }

  @Test
  void enrichirLot_memeNomAMoinsDe300m_retientLeWikidata() {
    when(client.rechercherLieux(anyString(), any()))
        .thenReturn(
            new ClientNominatim.ResultatRecherche(
                List.of(lieu("La Cigale", 48.88230, 2.34050, "Q2385432")), true));

    final Map<UUID, EnrichissementSalle> resultats = source.enrichirLot(List.of(LA_CIGALE));

    assertEquals("Q2385432", resultats.get(ID).wikidataId());
    assertEquals("https://www.lacigale.fr", resultats.get(ID).website());
  }

  @Test
  void enrichirLot_homonymeEloigne_neRetientRien() {
    when(client.rechercherLieux(anyString(), any()))
        .thenReturn(
            new ClientNominatim.ResultatRecherche(
                List.of(lieu("La Cigale", 48.88760, 2.34040, "Q999")), true));

    assertTrue(source.enrichirLot(List.of(LA_CIGALE)).isEmpty());
  }

  @Test
  void enrichirLot_salleSansPosition_nInterrogePasNominatim() {
    final SalleAEnrichir sansPosition =
        new SalleAEnrichir(ID, "La Cigale", "la cigale", "Paris", "75", null, null, null);

    assertTrue(source.enrichirLot(List.of(sansPosition)).isEmpty());
    verify(client, never()).rechercherLieux(anyString(), any());
  }

  @Test
  void enrichirLot_appelEnEchec_estCompteDansLeBilan() {
    when(client.rechercherLieux(anyString(), any()))
        .thenReturn(new ClientNominatim.ResultatRecherche(List.of(), false));

    source.enrichirLot(List.of(LA_CIGALE));

    assertEquals(1, source.preleverBilan().erreursHttp());
  }

  @Test
  void enrichirLot_nomCompletInconnu_retenteAvecLeNomTronque() {
    final SalleAEnrichir sirene =
        new SalleAEnrichir(
            ID,
            "LA SIRENE - Espace Musiques Actuelles",
            "la sirene",
            null,
            "17",
            null,
            46.15,
            -1.17);
    when(client.rechercherLieux(
            org.mockito.ArgumentMatchers.eq("LA SIRENE - Espace Musiques Actuelles"), any()))
        .thenReturn(new ClientNominatim.ResultatRecherche(List.of(), true));
    when(client.rechercherLieux(org.mockito.ArgumentMatchers.eq("LA SIRENE"), any()))
        .thenReturn(
            new ClientNominatim.ResultatRecherche(
                List.of(lieu("La Sirène", 46.15005, -1.17005, "Q3209460")), true));

    assertEquals("Q3209460", source.enrichirLot(List.of(sirene)).get(ID).wikidataId());
  }
}
