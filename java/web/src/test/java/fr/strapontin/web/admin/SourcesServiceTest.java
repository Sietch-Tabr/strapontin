/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.QuotaTM;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.web.salle.SalleService;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link SourcesService}.
 *
 * @since 2.3.0
 * @author Kwaite
 */
class SourcesServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private SourcesSallesListingService sourcesSallesListingService;
  private SourcesService sourcesService;
  private EventimSource eventim;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    sourcesSallesListingService = mock(SourcesSallesListingService.class);
    eventim = mock(EventimSource.class);
    sourcesService =
        new SourcesService(
            jdbc,
            new ObjectMapper(),
            eventim,
            mock(SalleService.class),
            sourcesSallesListingService);
  }

  @Test
  @SuppressWarnings("unchecked")
  void lireSources_sansQuota_retourneSourcesAvecEtatOk() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Timestamp.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);

    final Map<String, Object> resultat = sourcesService.lireSources();

    assertNotNull(resultat.get("sources"));
    final List<StatutSource> sources = (List<StatutSource>) resultat.get("sources");
    assertEquals(4, sources.size());

    final StatutSource tm = sources.get(0);
    assertEquals("ticketmaster", tm.id());
    assertEquals("ok", tm.etatQuota());
    assertNull(tm.quotaTM());
    assertEquals(0, tm.nbSallesConfigurees());
    assertEquals(0, tm.nbArtistesConfigures());

    final StatutSource eventim = sources.get(1);
    assertEquals("eventim", eventim.id());
    assertEquals(0, eventim.nbSallesConfigurees());
    assertEquals(0, eventim.nbArtistesConfigures());

    final StatutSource deezer = sources.get(2);
    assertEquals("deezer", deezer.id());
    assertNull(deezer.nbSallesConfigurees());
    assertEquals(0, deezer.nbArtistesConfigures());

    final StatutSource wikidata = sources.get(3);
    assertEquals("wikidata", wikidata.id());
    assertEquals(0, wikidata.nbSallesConfigurees());
    assertEquals(0, wikidata.nbArtistesConfigures());
  }

  // les comptages par source doivent bien interroger salle_sources/artiste_sources avec
  // le littéral de source correspondant (ticketmaster/eventim), pas une requête générique.
  @Test
  @SuppressWarnings("unchecked")
  void lireSources_comptesParSource_interrogeTicketmasterEtEventim() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Timestamp.class))).thenReturn(null);
    final ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    when(jdbc.queryForObject(anyString(), paramsCaptor.capture(), eq(Integer.class))).thenReturn(0);

    sourcesService.lireSources();

    final List<Object> sourcesInterrogees =
        paramsCaptor.getAllValues().stream()
            .map(m -> m.get("source"))
            .filter(Objects::nonNull)
            .toList();
    assertTrue(sourcesInterrogees.contains("ticketmaster"));
    assertTrue(sourcesInterrogees.contains("eventim"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void lireSources_avecQuotaCritique_retourneEtatCritique() throws Exception {
    final QuotaTM quota = new QuotaTM(90, 1000, 0L, "2026-06-19T10:00:00Z");
    final String quotaJson = new ObjectMapper().writeValueAsString(quota);
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of(quotaJson));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Timestamp.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);

    final Map<String, Object> resultat = sourcesService.lireSources();
    final List<StatutSource> sources = (List<StatutSource>) resultat.get("sources");

    final StatutSource tm = sources.get(0);
    assertNotNull(tm.quotaTM());
    assertEquals(90, tm.quotaTM().dispo());
    assertEquals("critique", tm.etatQuota());
  }

  // L'identifiant doit être celui que produit l'ingestion d'un concert de ce lieu, pour qu'un
  // concert déjà observé s'y rattache ; le libellé exact est conservé pour le filtre Eventim.
  @Test
  void mettreAJourLieuEventim_libelleValide_upsertAvecIdentifiantInterne() {
    final UUID uuid = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());

    sourcesService.mettreAJourLieuEventim(uuid, " LEMPREINTE SAVIGNY LE TEMPLE CEDEX ");

    verify(jdbc)
        .update(
            argThat((String sql) -> sql.contains("INSERT INTO salle_sources")),
            argThat(
                (Map<String, ?> params) ->
                    "internal:lempreinte savigny le temple cedex".equals(params.get("identifiant"))
                        && "LEMPREINTE SAVIGNY LE TEMPLE CEDEX".equals(params.get("libelle"))));
  }

  // un lieu déjà rattaché à une autre salle ne doit jamais être volé silencieusement.
  @Test
  void mettreAJourLieuEventim_lieuDejaRattacheAUneAutreSalle_leveErreurMetier409() {
    final UUID uuid = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("Autre Salle"));

    final fr.strapontin.web.ErreurMetier erreur =
        org.junit.jupiter.api.Assertions.assertThrows(
            fr.strapontin.web.ErreurMetier.class,
            () -> sourcesService.mettreAJourLieuEventim(uuid, "Accor Arena"));

    assertEquals(409, erreur.getStatus());
    verify(jdbc, org.mockito.Mockito.never())
        .update(argThat((String sql) -> sql.contains("INSERT INTO salle_sources")), anyMap());
  }

  @Test
  void mettreAJourLieuEventim_libelleVideOuNull_retireLeLibelle() {
    final UUID uuid = UUID.randomUUID();

    sourcesService.mettreAJourLieuEventim(uuid, "  ");
    sourcesService.mettreAJourLieuEventim(uuid, null);

    verify(jdbc, org.mockito.Mockito.times(2))
        .update(argThat((String sql) -> sql.contains("SET libelle_source = NULL")), anyMap());
  }

  // le détail de la construction SQL (HAVING, colonne id) est testé directement dans
  // SourcesSallesListingServiceTest — ici, seule la délégation compte.
  @Test
  void listerSalles_nominal_delegueAuServiceDeListage() {
    final ReponseSallesSources attendue = new ReponseSallesSources(List.of(), 0, 1, 0L);
    when(sourcesSallesListingService.lister(false, 0, "olympia")).thenReturn(attendue);

    final ReponseSallesSources reponse = sourcesService.listerSalles(false, 0, "olympia");

    assertEquals(attendue, reponse);
  }

  @Test
  void testerLieuEventim_delegueAuComptageEventim() {
    when(eventim.compterConcertsLieu("Accor Arena")).thenReturn(12);

    assertEquals(12, sourcesService.testerLieuEventim("Accor Arena"));
  }
}
