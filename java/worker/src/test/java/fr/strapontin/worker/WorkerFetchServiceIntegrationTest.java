/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.TicketmasterSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests d'intégration de [WorkerFetchService] avec PostgreSQL réel (Testcontainers).
 *
 * <p>Couvre spécifiquement la restriction de {@code fetch:salles:all} aux salles suivies le filtre
 * {@code EXISTS (... FROM user_salles...)} de la requête SQL ne peut être vérifié qu'avec une
 * vraie base, les tests unitaires de {@link WorkerFetchServiceTest} mockant {@code jdbc} avec un
 * matcher {@code anyString()} qui ne distingue pas les variantes de la requête.
 *
 * @since 2.7.3
 * @author Kwaite
 */
class WorkerFetchServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean private TicketmasterSource ticketmasterSource;
  @MockitoBean private EventimSource eventimSource;

  @Autowired private WorkerFetchService fetchService;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void nettoyerBase() {
    jdbc.update("DELETE FROM user_matches", Map.of());
    jdbc.update("DELETE FROM user_salles", Map.of());
    jdbc.update("DELETE FROM user_artistes", Map.of());
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM artiste_sources", Map.of());
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM artistes", Map.of());
    jdbc.update("DELETE FROM users", Map.of());
  }

  private void insererUser(final String id) {
    jdbc.update(
        "INSERT INTO users (id, email) VALUES (:id, :email)",
        Map.of("id", id, "email", id + "@test.fr"));
  }

  private UUID insererSalleAvecSourceTm(final String salleCle, final String venueId) {
    final UUID salleId =
        jdbc.queryForObject(
            "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES (:id, :nom, :norm)"
                + " ON CONFLICT (salle_cle) DO UPDATE SET nom = EXCLUDED.nom RETURNING id",
            Map.of("id", salleCle, "nom", salleCle, "norm", salleCle),
            (rs, _) -> rs.getObject("id", UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:salleId AS uuid), 'ticketmaster', :venueId)",
        Map.of("salleId", salleId.toString(), "venueId", venueId));
    return salleId;
  }

  private void insererUserSalle(final String userId, final UUID salleId) {
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_id) VALUES (:userId, CAST(:salleId AS uuid))",
        Map.of("userId", userId, "salleId", salleId.toString()));
  }

  @Test
  void executerFetchSallesAll_salleSuivie_estFetchee() {
    insererUser("u1");
    final UUID salleSuivie = insererSalleAvecSourceTm("salle-suivie", "venue-suivie");
    insererUserSalle("u1", salleSuivie);
    when(ticketmasterSource.accepteIdentifiantSalle(startsWith("tm:"))).thenReturn(true);
    when(ticketmasterSource.fetchSalle(startsWith("tm:"))).thenReturn(List.<ConcertBrut>of());

    fetchService.executerFetchSallesAll();

    verify(ticketmasterSource).fetchSalle("tm:venue-suivie");
  }

  @Test
  void executerFetchSallesAll_salleNonSuivie_nEstJamaisFetchee() {
    insererUser("u1");
    insererSalleAvecSourceTm("salle-non-suivie", "venue-non-suivie");
    when(ticketmasterSource.accepteIdentifiantSalle(startsWith("tm:"))).thenReturn(true);
    when(ticketmasterSource.fetchSalle(startsWith("tm:"))).thenReturn(List.<ConcertBrut>of());

    fetchService.executerFetchSallesAll();

    verify(ticketmasterSource, never()).fetchSalle("tm:venue-non-suivie");
  }

  private UUID insererArtisteSuivi(final String userId, final String nom) {
    final UUID artisteId =
        jdbc.queryForObject(
            "INSERT INTO artistes (nom, nom_norm) VALUES (:nom, :norm) RETURNING id",
            Map.of("nom", nom, "norm", nom.toLowerCase()),
            (rs, _) -> rs.getObject("id", UUID.class));
    jdbc.update(
        "INSERT INTO user_artistes (user_id, artiste_id, artiste_affiche)"
            + " VALUES (:userId, CAST(:artisteId AS uuid), :nom)",
        Map.of("userId", userId, "artisteId", artisteId.toString(), "nom", nom));
    return artisteId;
  }

  private void insererConcertAncien(final UUID artisteId, final String artisteNorm) {
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, artiste_id, updated_at)"
                + " VALUES (:artisteNorm, :date, 'salle test', CAST(:artisteId AS uuid),"
                + " now() - interval '1 hour') RETURNING id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("artisteNorm", artisteNorm.toLowerCase())
                .addValue("date", LocalDate.now().plusMonths(1))
                .addValue("artisteId", artisteId.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), 'ticketmaster', :artiste, 'Salle Test')",
        Map.of("id", evenementId.toString(), "artiste", artisteNorm));
  }

  // Un concert précédemment vu mais absent de la réponse fraîche de ce cycle doit voir son
  // compteur d'absences consécutives incrémenté — pas seulement en théorie (mock), mais avec la
  // vraie syntaxe SQL exécutée contre PostgreSQL.
  @Test
  void executerFetchArtistesBatch_concertNonReconfirme_incrementeLesAbsencesConsecutives() {
    insererUser("u1");
    final UUID artisteId = insererArtisteSuivi("u1", "Indila");
    insererConcertAncien(artisteId, "Indila");
    when(ticketmasterSource.rechercherAttractionId(anyString())).thenReturn(null);
    when(ticketmasterSource.fetchArtiste(anyString())).thenReturn(List.<ConcertBrut>of());
    when(ticketmasterSource.fetchArtistesLot(any())).thenReturn(List.<ConcertBrut>of());
    when(eventimSource.fetchArtiste(anyString(), any(), any())).thenReturn(List.<ConcertBrut>of());

    fetchService.executerFetchArtistesBatch();

    final Integer absences =
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM concerts WHERE artiste_id = CAST(:id AS uuid)",
            Map.of("id", artisteId.toString()),
            Integer.class);
    assertEquals(1, absences);
  }

  private void insererConcertAncienSurSalle(final UUID salleId) {
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, salle_id, updated_at)"
                + " VALUES ('artiste salle', :date, 'salle test', CAST(:salleId AS uuid),"
                + " now() - interval '1 hour') RETURNING id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("date", LocalDate.now().plusMonths(1))
                .addValue("salleId", salleId.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), 'ticketmaster', 'Artiste Salle', 'Salle Test')",
        Map.of("id", evenementId.toString()));
  }

  // Même régression que executerFetchArtistesBatch_concertNonReconfirme_..., côté salle suivie
  // (fetch:salles:all) — SQL_ABSENCES_SALLE était du code mort, jamais atteint.
  @Test
  void executerFetchSallesAll_concertNonReconfirme_incrementeLesAbsencesConsecutives() {
    insererUser("u1");
    final UUID salleSuivie = insererSalleAvecSourceTm("salle-suivie", "venue-suivie");
    insererUserSalle("u1", salleSuivie);
    insererConcertAncienSurSalle(salleSuivie);
    when(ticketmasterSource.accepteIdentifiantSalle(startsWith("tm:"))).thenReturn(true);
    when(ticketmasterSource.fetchSalle(startsWith("tm:"))).thenReturn(List.<ConcertBrut>of());

    fetchService.executerFetchSallesAll();

    final Integer absences =
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM concerts WHERE salle_id = CAST(:id AS uuid)",
            Map.of("id", salleSuivie.toString()),
            Integer.class);
    assertEquals(1, absences);
  }

  private void insererUserConfigDept(final String userId, final String dept) {
    jdbc.update(
        "INSERT INTO user_config (user_id, departements) VALUES (:userId, ARRAY[:dept]::text[])",
        Map.of("userId", userId, "dept", dept));
  }

  // Un concert découvert par fetch:region (browse-only) et jamais suivi (ni par artiste, ni par
  // salle) doit quand même voir son compteur d'absences évoluer — sans quoi il reste
  // indéfiniment invisible à toute détection de péremption.
  @Test
  void executerFetchRegion_concertNiArtisteNiSalleSuivi_incrementeLesAbsencesConsecutives() {
    insererUser("u1");
    insererUserConfigDept("u1", "75");
    final UUID salleNonSuivie = insererSalleAvecSourceTm("salle-decouverte", "venue-decouverte");
    jdbc.update(
        "UPDATE salles SET dept = '75' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleNonSuivie.toString()));
    insererConcertAncienSurSalle(salleNonSuivie);
    jdbc.update(
        "UPDATE evenements SET dept = '75' WHERE salle_id = CAST(:id AS uuid)",
        Map.of("id", salleNonSuivie.toString()));
    when(ticketmasterSource.fetchRegion(any(), any())).thenReturn(List.<ConcertBrut>of());
    when(eventimSource.fetchRegion(any(), any())).thenReturn(List.<ConcertBrut>of());

    fetchService.executerFetchRegion();

    final Integer absences =
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM concerts WHERE salle_id = CAST(:id AS uuid)",
            Map.of("id", salleNonSuivie.toString()),
            Integer.class);
    assertEquals(1, absences);
  }
}
