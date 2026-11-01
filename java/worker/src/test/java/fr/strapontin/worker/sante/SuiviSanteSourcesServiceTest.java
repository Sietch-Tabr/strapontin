/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link SuiviSanteSourcesService} (phase 1).
 *
 * @since 2.6.0
 * @author Kwaite
 */
class SuiviSanteSourcesServiceTest {

  private static ConcertBrut concertAvecArtisteSourceId(final String artisteSourceId) {
    return ConcertBrut.nouveau()
        .artiste("Artiste")
        .date("2026-08-01")
        .salle("Salle")
        .ville("Ville")
        .dept("75")
        .source("ticketmaster")
        .artisteSourceId(artisteSourceId)
        .construire();
  }

  @Test
  void enregistrerResultatSalle_identifiantSansPrefixe_neFaitRien() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    service.enregistrerResultatSalle("sans-prefixe", 1);

    verify(jdbc, org.mockito.Mockito.never())
        .update(anyString(), org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void enregistrerResultatSalle_succes_metAJourAvecSuccesTrue() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    service.enregistrerResultatSalle("ticketmaster:rZFSnyZdFvA", 3);

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("salle_sources"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p ->
                    Boolean.TRUE.equals(p.get("succes"))
                        && "ticketmaster".equals(p.get("source"))
                        && "rZFSnyZdFvA".equals(p.get("identifiant"))));
  }

  @Test
  void enregistrerResultatSalle_prefixeAbregeTm_normaliseVersTicketmaster() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    service.enregistrerResultatSalle("tm:rZFSnyZdFvA", 0);

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("salle_sources"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p ->
                    "ticketmaster".equals(p.get("source"))
                        && Boolean.FALSE.equals(p.get("succes"))));
  }

  @Test
  void enregistrerResultatSalle_eventim_prefixePreserve() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    service.enregistrerResultatSalle("eventim:internal:lempreinte", 1);

    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p ->
                    "eventim".equals(p.get("source"))
                        && "internal:lempreinte".equals(p.get("identifiant"))));
  }

  @Test
  void enregistrerResultatArtistesLot_unSurDeuxAvecConcert_succesEtEchecCorrects() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    final List<String> attractionIds = List.of("K1", "K2", "K3");
    final List<ConcertBrut> concertsLot = List.of(concertAvecArtisteSourceId("ticketmaster:K2"));

    service.enregistrerResultatArtistesLot(attractionIds, concertsLot);

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("artiste_sources"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> "K1".equals(p.get("identifiant")) && Boolean.FALSE.equals(p.get("succes"))));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("artiste_sources"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> "K2".equals(p.get("identifiant")) && Boolean.TRUE.equals(p.get("succes"))));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("artiste_sources"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> "K3".equals(p.get("identifiant")) && Boolean.FALSE.equals(p.get("succes"))));
  }

  @Test
  void enregistrerResultatArtistesLot_succes_effaceNecessiteInterventionArtisteSources() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    service.enregistrerResultatArtistesLot(
        List.of("K1"), List.of(concertAvecArtisteSourceId("ticketmaster:K1")));

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("necessite_intervention_le"),
            org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void enregistrerResultatSalle_succes_neReferencePasNecessiteInterventionSalleSources() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    service.enregistrerResultatSalle("ticketmaster:rZFSnyZdFvA", 1);

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(sql -> !sql.contains("necessite_intervention_le")),
            org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void enregistrerResultatSalle_exceptionJdbc_neRelancePasException() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenThrow(new RuntimeException("DB down"));
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> service.enregistrerResultatSalle("ticketmaster:x", 1));
  }

  @Test
  void enregistrerResultatArtistesLot_flagPoseEtNomCoherent_effaceInterventionEtAppelleTm() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(List.of(Map.of("necessite_intervention_le", Instant.now(), "nom", "Calogero")));
    when(tm.recupererNomAttraction("K1")).thenReturn("Calogero");
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    service.enregistrerResultatArtistesLot(
        List.of("K1"), List.of(concertAvecArtisteSourceId("ticketmaster:K1")));

    verify(tm).recupererNomAttraction("K1");
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("necessite_intervention_le"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> Boolean.TRUE.equals(p.get("effacerIntervention"))));
  }

  @Test
  void enregistrerResultatArtistesLot_flagPoseEtNomIncoherent_neEffacePasIntervention() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(List.of(Map.of("necessite_intervention_le", Instant.now(), "nom", "Furies")));
    when(tm.recupererNomAttraction("K1")).thenReturn("Love & Furies");
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    service.enregistrerResultatArtistesLot(
        List.of("K1"), List.of(concertAvecArtisteSourceId("ticketmaster:K1")));

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("necessite_intervention_le"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> Boolean.FALSE.equals(p.get("effacerIntervention"))));
  }

  @Test
  void enregistrerResultatArtistesLot_flagPoseEtTmInjoignable_neEffacePasIntervention() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(List.of(Map.of("necessite_intervention_le", Instant.now(), "nom", "Calogero")));
    when(tm.recupererNomAttraction("K1")).thenReturn(null);
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    service.enregistrerResultatArtistesLot(
        List.of("K1"), List.of(concertAvecArtisteSourceId("ticketmaster:K1")));

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("necessite_intervention_le"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> Boolean.FALSE.equals(p.get("effacerIntervention"))));
  }

  @Test
  void enregistrerResultatArtistesLot_flagNonPose_effaceSansAppelerTm() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(List.of(Map.of("nom", "Calogero")));
    final SuiviSanteSourcesService service =
        new SuiviSanteSourcesService(jdbc, tm, mock(SignalementMatchingService.class));

    service.enregistrerResultatArtistesLot(
        List.of("K1"), List.of(concertAvecArtisteSourceId("ticketmaster:K1")));

    verify(tm, org.mockito.Mockito.never()).recupererNomAttraction(anyString());
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("necessite_intervention_le"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> Boolean.TRUE.equals(p.get("effacerIntervention"))));
  }

  @Test
  void estChuteBrutale_volumeSignificatifTombeSousTrentePourCent_retourneVrai() {
    assertTrue(SuiviSanteSourcesService.estChuteBrutale(11, 1));
    assertTrue(SuiviSanteSourcesService.estChuteBrutale(10, 3));
  }

  @Test
  void estChuteBrutale_baisseOrdinaireOuVolumeFaible_retourneFaux() {
    assertFalse(SuiviSanteSourcesService.estChuteBrutale(10, 4));
    assertFalse(SuiviSanteSourcesService.estChuteBrutale(4, 0));
    assertFalse(SuiviSanteSourcesService.estChuteBrutale(10, 12));
  }
}
