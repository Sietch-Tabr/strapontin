/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ConcertBrut;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link SuiviSanteSourcesService} (#706 phase 1).
///
/// @since 2.6.0
/// @author Kwaite
class SuiviSanteSourcesServiceTest {

  private static ConcertBrut concertAvecArtisteSourceId(String artisteSourceId) {
    return new ConcertBrut(
        "Artiste",
        "2026-08-01",
        null,
        "Salle",
        "Ville",
        "75",
        null,
        "ticketmaster",
        null,
        null,
        null,
        null,
        null,
        null,
        artisteSourceId);
  }

  @Test
  void enregistrerResultatSalle_identifiantSansPrefixe_neFaitRien() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    SuiviSanteSourcesService service = new SuiviSanteSourcesService(jdbc);

    service.enregistrerResultatSalle("sans-prefixe", 1);

    verify(jdbc, org.mockito.Mockito.never())
        .update(anyString(), org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void enregistrerResultatSalle_succes_metAJourAvecSuccesTrue() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    SuiviSanteSourcesService service = new SuiviSanteSourcesService(jdbc);

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
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    SuiviSanteSourcesService service = new SuiviSanteSourcesService(jdbc);

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
  void enregistrerResultatSalle_digitick_prefixePreserve() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    SuiviSanteSourcesService service = new SuiviSanteSourcesService(jdbc);

    service.enregistrerResultatSalle("digitick:lempreinte", 1);

    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p ->
                    "digitick".equals(p.get("source"))
                        && "lempreinte".equals(p.get("identifiant"))));
  }

  @Test
  void enregistrerResultatArtistesLot_unSurDeuxAvecConcert_succesEtEchecCorrects() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    SuiviSanteSourcesService service = new SuiviSanteSourcesService(jdbc);

    List<String> attractionIds = List.of("K1", "K2", "K3");
    List<ConcertBrut> concertsLot = List.of(concertAvecArtisteSourceId("ticketmaster:K2"));

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
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    SuiviSanteSourcesService service = new SuiviSanteSourcesService(jdbc);

    service.enregistrerResultatArtistesLot(
        List.of("K1"), List.of(concertAvecArtisteSourceId("ticketmaster:K1")));

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("necessite_intervention_le"),
            org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void enregistrerResultatSalle_succes_neReferencePasNecessiteInterventionSalleSources() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    SuiviSanteSourcesService service = new SuiviSanteSourcesService(jdbc);

    service.enregistrerResultatSalle("ticketmaster:rZFSnyZdFvA", 1);

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(sql -> !sql.contains("necessite_intervention_le")),
            org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void enregistrerResultatSalle_exceptionJdbc_neRelancePasException() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenThrow(new RuntimeException("DB down"));
    SuiviSanteSourcesService service = new SuiviSanteSourcesService(jdbc);

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> service.enregistrerResultatSalle("ticketmaster:x", 1));
  }
}
