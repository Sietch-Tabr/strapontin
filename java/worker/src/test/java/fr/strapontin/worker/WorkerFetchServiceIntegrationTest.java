/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.QuotaTM;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.LieuEventim;
import fr.strapontin.sources.ReponseIncompleteException;
import fr.strapontin.sources.ResultatRegion;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.quota.GestionQuotaTM;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
  @Autowired private GestionQuotaTM gestionQuotaTM;

  @BeforeEach
  void nettoyerBase() {
    // Le nom de la source indexe son horizon de couverture (absences par salle).
    when(ticketmasterSource.nom()).thenReturn("ticketmaster");
    when(eventimSource.nom()).thenReturn("eventim");
    jdbc.update("DELETE FROM signalements_matching", Map.of());
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
    when(ticketmasterSource.fetchSalle(startsWith("tm:"), any()))
        .thenReturn(List.<ConcertBrut>of());

    fetchService.executerFetchSallesAll();

    verify(ticketmasterSource).fetchSalle("tm:venue-suivie", null);
  }

  @Test
  void executerFetchSallesAll_salleNonSuivie_nEstJamaisFetchee() {
    insererUser("u1");
    insererSalleAvecSourceTm("salle-non-suivie", "venue-non-suivie");
    when(ticketmasterSource.accepteIdentifiantSalle(startsWith("tm:"))).thenReturn(true);
    when(ticketmasterSource.fetchSalle(startsWith("tm:"), any()))
        .thenReturn(List.<ConcertBrut>of());

    fetchService.executerFetchSallesAll();

    verify(ticketmasterSource, never()).fetchSalle("tm:venue-non-suivie", null);
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
            new MapSqlParameterSource()
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

  // Concert renvoyé par la recherche par département, dans la salle déjà connue.
  private static ConcertBrut concertRegion(
      final String salle, final String classification, final String genre) {
    return ConcertBrut.nouveau()
        .artiste("Autre Artiste")
        .date(LocalDate.now().plusMonths(2).toString())
        .heure("20:00")
        .salle(salle)
        .ville("Paris")
        .dept("75")
        .source("ticketmaster")
        .url("https://www.ticketmaster.fr/fr/manifestation/autre/idmanif/1")
        .classification(classification)
        .genre(genre)
        .construire();
  }

  private void insererConcertAncienSurSalle(final UUID salleId) {
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, salle_id, updated_at)"
                + " VALUES ('artiste salle', :date, 'salle test', CAST(:salleId AS uuid),"
                + " now() - interval '1 hour') RETURNING id",
            new MapSqlParameterSource()
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
    when(ticketmasterSource.fetchSalle(startsWith("tm:"), any()))
        .thenReturn(List.<ConcertBrut>of());
    when(ticketmasterSource.joursCouvertsParSalle()).thenReturn(180);

    fetchService.executerFetchSallesAll();

    final Integer absences =
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM concerts WHERE salle_id = CAST(:id AS uuid)",
            Map.of("id", salleSuivie.toString()),
            Integer.class);
    assertEquals(1, absences);
  }

  // Le Roi Lion (dev, 03/10/2026) : quota Ticketmaster refusé, la salle semblait vide et chaque
  // séance gagnait une absence jusqu'à être fusionnée à tort dans une autre.
  @Test
  void executerFetchSallesAll_reponseIncomplete_neCompteAucuneAbsence() {
    insererUser("u1");
    final UUID salleSuivie = insererSalleAvecSourceTm("salle-suivie", "venue-suivie");
    insererUserSalle("u1", salleSuivie);
    insererConcertAncienSurSalle(salleSuivie);
    when(ticketmasterSource.accepteIdentifiantSalle(startsWith("tm:"))).thenReturn(true);
    when(ticketmasterSource.fetchSalle(startsWith("tm:"), any()))
        .thenThrow(new ReponseIncompleteException("quota", List.of(), null));
    when(ticketmasterSource.joursCouvertsParSalle()).thenReturn(180);

    fetchService.executerFetchSallesAll();

    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM concerts WHERE salle_id = CAST(:id AS uuid)",
            Map.of("id", salleSuivie.toString()),
            Integer.class));
  }

  @Test
  void executerFetchArtistesBatch_reponseIncomplete_neCompteAucuneAbsence() {
    insererUser("u1");
    final UUID artisteId = insererArtisteSuivi("u1", "Indila");
    insererConcertAncien(artisteId, "Indila");
    when(ticketmasterSource.rechercherAttractionId(anyString())).thenReturn(null);
    when(ticketmasterSource.fetchArtiste(anyString()))
        .thenThrow(new ReponseIncompleteException("quota", List.of(), null));
    when(eventimSource.fetchArtiste(anyString(), any(), any())).thenReturn(List.<ConcertBrut>of());

    fetchService.executerFetchArtistesBatch();

    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM concerts WHERE artiste_id = CAST(:id AS uuid)",
            Map.of("id", artisteId.toString()),
            Integer.class));
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
    when(ticketmasterSource.fetchRegionCouverte(any(), any()))
        .thenReturn(
            new ResultatRegion(
                List.of(concertRegion("salle-decouverte", null, null)),
                LocalDate.now().plusMonths(6)));
    when(eventimSource.fetchRegionCouverte(any(), any()))
        .thenReturn(new ResultatRegion(List.of(), null));

    fetchService.executerFetchRegion();

    final Integer absences =
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM concerts WHERE salle_id = CAST(:id AS uuid)"
                + " AND artiste_norm = 'artiste salle'",
            Map.of("id", salleNonSuivie.toString()),
            Integer.class);
    assertEquals(1, absences);
  }

  // Spectacle d'humour entré par une salle autrefois suivie : la recherche par département,
  // limitée à la musique, ne le renverra jamais et ne doit pas le croire disparu.
  @Test
  void executerFetchRegion_classementJamaisRenvoyeParLaRecherche_neCompteAucuneAbsence() {
    insererUser("u1");
    insererUserConfigDept("u1", "75");
    final UUID salleNonSuivie = insererSalleAvecSourceTm("salle-humour", "venue-humour");
    jdbc.update(
        "UPDATE salles SET dept = '75' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleNonSuivie.toString()));
    insererConcertAncienSurSalle(salleNonSuivie);
    jdbc.update(
        "UPDATE evenements SET dept = '75', classification = 'theatre', genre = 'Humour'"
            + " WHERE salle_id = CAST(:id AS uuid)",
        Map.of("id", salleNonSuivie.toString()));
    when(ticketmasterSource.fetchRegionCouverte(any(), any()))
        .thenReturn(
            new ResultatRegion(
                List.of(concertRegion("salle-humour", "music", "Rock")),
                LocalDate.now().plusMonths(6)));
    when(eventimSource.fetchRegionCouverte(any(), any()))
        .thenReturn(new ResultatRegion(List.of(), null));

    fetchService.executerFetchRegion();

    final Integer absences =
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM concerts WHERE salle_id = CAST(:id AS uuid)"
                + " AND artiste_norm = 'artiste salle'",
            Map.of("id", salleNonSuivie.toString()),
            Integer.class);
    assertEquals(0, absences);
  }

  // Un concert au-delà de la dernière date couverte par la requête (plafond de pagination,
  // fenêtre) n'a pas été cherché : il ne doit pas compter d'absence.
  @Test
  void executerFetchRegion_concertAuDelaDeLHorizonCouvert_neCompteAucuneAbsence() {
    insererUser("u1");
    insererUserConfigDept("u1", "75");
    final UUID salleNonSuivie = insererSalleAvecSourceTm("salle-horizon", "venue-horizon");
    jdbc.update(
        "UPDATE salles SET dept = '75' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleNonSuivie.toString()));
    insererConcertAncienSurSalle(salleNonSuivie);
    jdbc.update(
        "UPDATE evenements SET dept = '75' WHERE salle_id = CAST(:id AS uuid)",
        Map.of("id", salleNonSuivie.toString()));
    when(ticketmasterSource.fetchRegionCouverte(any(), any()))
        .thenReturn(new ResultatRegion(List.of(), LocalDate.now().plusDays(7)));
    when(eventimSource.fetchRegionCouverte(any(), any()))
        .thenReturn(new ResultatRegion(List.of(), null));

    fetchService.executerFetchRegion();

    final Integer absences =
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM concerts WHERE salle_id = CAST(:id AS uuid)",
            Map.of("id", salleNonSuivie.toString()),
            Integer.class);
    assertEquals(0, absences);
  }

  // Une source qui n'a pas été interrogée pour la salle (aucun identifiant chez elle) ne
  // garantit rien : un concert qu'elle seule observe ne compte pas d'absence.
  @Test
  void executerFetchSallesAll_concertObserveParUneSourceNonInterrogee_neCompteAucuneAbsence() {
    insererUser("u1");
    final UUID salleSuivie = insererSalleAvecSourceTm("salle-eventim", "venue-eventim");
    insererUserSalle("u1", salleSuivie);
    insererConcertAncienSurSalle(salleSuivie);
    jdbc.update(
        "UPDATE observations SET source = 'eventim' WHERE evenement_id IN"
            + " (SELECT id FROM evenements WHERE salle_id = CAST(:id AS uuid))",
        Map.of("id", salleSuivie.toString()));
    when(ticketmasterSource.accepteIdentifiantSalle(startsWith("tm:"))).thenReturn(true);
    when(ticketmasterSource.fetchSalle(startsWith("tm:"), any()))
        .thenReturn(List.<ConcertBrut>of());
    when(ticketmasterSource.joursCouvertsParSalle()).thenReturn(180);

    fetchService.executerFetchSallesAll();

    final Integer absences =
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM concerts WHERE salle_id = CAST(:id AS uuid)",
            Map.of("id", salleSuivie.toString()),
            Integer.class);
    assertEquals(0, absences);
  }

  private void insererSourceEventim(
      final UUID salleId, final String identifiant, final String libelle) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, libelle_source)"
            + " VALUES (CAST(:salleId AS uuid), 'eventim', :identifiant, :libelle)",
        new MapSqlParameterSource()
            .addValue("salleId", salleId.toString())
            .addValue("identifiant", identifiant)
            .addValue("libelle", libelle));
  }

  // Eventim n'expose aucun identifiant de lieu : il s'interroge par libellé exact, et un lieu
  // dont le libellé est encore inconnu n'est pas interrogé — le libellé relevé lors d'un fetch
  // est ensuite enregistré pour les cycles suivants.
  @Test
  void executerFetchSallesAll_lieuEventim_interrogeParLibelleEtReleveLesLieuxObserves() {
    insererUser("u1");
    final UUID salleAvecLibelle = insererSalleAvecSourceTm("salle-eventim-1", "venue-e1");
    insererSourceEventim(salleAvecLibelle, "internal:accor arena", "Accor Arena");
    final UUID salleSansLibelle = insererSalleAvecSourceTm("salle-eventim-2", "venue-e2");
    insererSourceEventim(salleSansLibelle, "internal:olympia", null);
    insererUserSalle("u1", salleAvecLibelle);
    insererUserSalle("u1", salleSansLibelle);
    when(ticketmasterSource.accepteIdentifiantSalle(startsWith("tm:"))).thenReturn(true);
    when(ticketmasterSource.fetchSalle(startsWith("tm:"), any())).thenReturn(List.of());
    when(eventimSource.accepteIdentifiantSalle(startsWith("eventim:"))).thenReturn(true);
    when(eventimSource.fetchSalle(startsWith("eventim:"), any())).thenReturn(List.of());
    when(eventimSource.preleverLieuxObserves())
        .thenReturn(List.of(new LieuEventim("internal:olympia", "Olympia", "Paris", 48.87, 2.33)))
        .thenReturn(List.of());

    fetchService.executerFetchSallesAll();

    verify(eventimSource).fetchSalle("eventim:internal:accor arena", "Accor Arena");
    verify(eventimSource, never()).fetchSalle(eq("eventim:internal:olympia"), any());
    final Map<String, Object> lieu =
        jdbc.queryForMap(
            "SELECT libelle_source, ville_source, latitude FROM salle_sources"
                + " WHERE source = 'eventim' AND identifiant_externe = 'internal:olympia'",
            Map.of());
    assertEquals("Olympia", lieu.get("libelle_source"));
    assertEquals("Paris", lieu.get("ville_source"));
    assertEquals(48.87, lieu.get("latitude"));
  }

  // Un lieu passé à une autre billetterie garde souvent un concert listé : la chute par rapport
  // au volume précédent doit être signalée, pas seulement une salle devenue vide.
  @Test
  void executerFetchSallesAll_chuteBrutaleDuNombreDeConcerts_signaleLaSalle() {
    insererUser("u1");
    final UUID salle = insererSalleAvecSourceTm("salle-chute", "venue-chute");
    insererUserSalle("u1", salle);
    jdbc.update(
        "UPDATE salle_sources SET dernier_nb_resultats = 11 WHERE identifiant_externe ="
            + " 'venue-chute'",
        Map.of());
    when(ticketmasterSource.nom()).thenReturn("ticketmaster");
    when(ticketmasterSource.accepteIdentifiantSalle(startsWith("tm:"))).thenReturn(true);
    when(ticketmasterSource.fetchSalle(startsWith("tm:"), any())).thenReturn(List.of());

    fetchService.executerFetchSallesAll();

    final Integer signalements =
        jdbc.queryForObject(
            "SELECT count(*) FROM signalements_matching WHERE type = 'chute_resultats_salle'"
                + " AND entite_id = CAST(:id AS uuid) AND resolu_le IS NULL",
            Map.of("id", salle.toString()),
            Integer.class);
    assertEquals(1, signalements);
    final Integer dernier =
        jdbc.queryForObject(
            "SELECT dernier_nb_resultats FROM salle_sources WHERE identifiant_externe ="
                + " 'venue-chute'",
            Map.of(),
            Integer.class);
    assertEquals(0, dernier);
  }

  // Quota Ticketmaster sous le seuil des cycles : Ticketmaster est sauté, Eventim (sans quota)
  // continue ; jusqu'ici tout le job s'arrêtait, dates Eventim comprises.
  @Test
  void executerFetchRegion_quotaTicketmasterBas_interrogeQuandMemeEventim() {
    insererUser("u1");
    insererUserConfigDept("u1", "75");
    gestionQuotaTM.ecrireQuota(
        new QuotaTM(
            100, 5000, System.currentTimeMillis() + 3_600_000, java.time.Instant.now().toString()));
    when(eventimSource.fetchRegionCouverte(any(), any()))
        .thenReturn(new ResultatRegion(List.of(), null));
    try {
      fetchService.executerFetchRegion();

      verify(eventimSource).fetchRegionCouverte(any(), any());
      verify(ticketmasterSource, never()).fetchRegionCouverte(any(), any());
      assertEquals(
          "quota insuffisant",
          jdbc.queryForObject(
              "SELECT erreur FROM fetch_jobs WHERE type_job = 'fetch:region'",
              Map.of(),
              String.class));
    } finally {
      jdbc.update("DELETE FROM app_config WHERE cle = 'TM_QUOTA_JSON'", Map.of());
    }
  }
}
