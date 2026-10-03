/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires pour {@link WorkerPurgeService} (extrait de {@code WorkerService}).
 *
 * @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
class WorkerPurgeServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private PurgeCompteEmailService purgeCompteEmailService;
  private ConfigServiceWorker configService;
  private WorkerPurgeService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    final ResolveurNomCanonique resolveur = mock(ResolveurNomCanonique.class);
    final LogsAdminWorker logsAdminWorker = new LogsAdminWorker(jdbc, new ObjectMapper());
    final WorkerStatsService workerStatsService =
        new WorkerStatsService(
            List.<SourceConcerts>of(),
            tm,
            resolveur,
            jdbc,
            logsAdminWorker,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            new SuiviChroniciteService(jdbc, new ObjectMapper()),
            new DeriveVolumeFetchService(jdbc, logsAdminWorker));
    purgeCompteEmailService = mock(PurgeCompteEmailService.class);
    configService = mock(ConfigServiceWorker.class);
    service =
        new WorkerPurgeService(
            jdbc, workerStatsService, purgeCompteEmailService, configService, logsAdminWorker);
  }

  @Test
  void executerPurgerConcerts_supprimeConcertsPassesEtEnregistreStat() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(5);

    service.executerPurgerConcerts();

    verify(jdbc).update(contains("DELETE FROM evenements"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs (type_job"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs_historique"), anyMap());
  }

  // 10× le seuil de simple signalement "à vérifier" (WorkerFetchService.SEUIL_ABSENCES_CONSECUTIVES
  // = 5) — un état signalé ne doit pas rester affiché indéfiniment sans jamais évoluer.
  @Test
  void executerPurgerConcertsNonConfirmes_seuilCorrespondADixFoisLeSeuilDeSignalement() {
    assertEquals(50, WorkerPurgeService.SEUIL_PURGE_ABSENCES);
  }

  // Depuis #1091 : les concerts jamais reconfirmés sont flagués (suppression_suggeree_le), plus
  // jamais supprimés directement — un doublon de salle jamais fusionné entre sources, ou un
  // concert catalogué sous une autre attraction que l'artiste suivi, peuvent faire grimper ce
  // compteur pour un concert pourtant réel et toujours en vente. La suppression définitive
  // devient une action manuelle (onglet admin Concerts).
  @Test
  void executerPurgerConcertsNonConfirmes_signaleConcertsJamaisReconfirmesEtEnregistreStat() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(3);

    service.executerPurgerConcertsNonConfirmes();

    verify(jdbc)
        .update(
            contains("UPDATE evenements SET suppression_suggeree_le = now()"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> Integer.valueOf(50).equals(p.get("seuil"))));
    verify(jdbc)
        .update(
            contains("INSERT INTO fetch_jobs (type_job"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> "purger:concerts-non-confirmes".equals(p.get("typeJob"))));
  }

  @Test
  void executerPurgerConcertsNonConfirmes_erreurJdbc_enregistreStatAvecErreur() {
    when(jdbc.update(contains("UPDATE evenements SET suppression_suggeree_le"), anyMap()))
        .thenThrow(new RuntimeException("timeout"));

    assertDoesNotThrow(() -> service.executerPurgerConcertsNonConfirmes());

    verify(jdbc)
        .update(
            contains("INSERT INTO fetch_jobs (type_job"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p ->
                    "purger:concerts-non-confirmes".equals(p.get("typeJob"))
                        && "timeout".equals(p.get("erreur"))));
  }

  // Un concert sans salle_id ni artiste_id n'est jamais retouché par aucun job de fetch —
  // absences_consecutives reste figé à 0 et ne peut jamais déclencher
  // executerPurgerConcertsNonConfirmes. Le seuil calendaire indépendant sert de substitut.
  @Test
  void executerPurgerConcertsHorsPortee_signaleConcertsSansSalleNiArtisteEtEnregistreStat() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(2);

    service.executerPurgerConcertsHorsPortee();

    verify(jdbc)
        .update(
            contains("UPDATE evenements SET suppression_suggeree_le = now()"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> Integer.valueOf(30).equals(p.get("seuilJours"))));
    verify(jdbc)
        .update(
            contains("INSERT INTO fetch_jobs (type_job"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> "purger:concerts-hors-portee".equals(p.get("typeJob"))));
  }

  @Test
  void executerPurgerSalles_supprimeSallesOrphelinesEtEnregistreStat() {
    final List<UUID> candidats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    when(jdbc.query(
            contains("SELECT id FROM salles"),
            anyMap(),
            org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
        .thenReturn(candidats);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    service.executerPurgerSalles();

    verify(jdbc, times(3)).update(contains("DELETE FROM salles"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs (type_job"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs_historique"), anyMap());
  }

  @Test
  void executerPurgerSalles_conflitSurUneSalle_continueLesAutresEtCompteCorrectement() {
    final UUID bloquee = UUID.randomUUID();
    final UUID libre1 = UUID.randomUUID();
    final UUID libre2 = UUID.randomUUID();
    when(jdbc.query(
            contains("SELECT id FROM salles"),
            anyMap(),
            org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
        .thenReturn(List.of(bloquee, libre1, libre2));
    when(jdbc.update(
            contains("DELETE FROM salles"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> bloquee.equals(p.get("id")))))
        .thenThrow(new DuplicateKeyException("clé dupliquée"));
    when(jdbc.update(
            contains("DELETE FROM salles"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> !bloquee.equals(p.get("id")))))
        .thenReturn(1);
    when(jdbc.update(contains("INSERT INTO fetch_jobs"), anyMap())).thenReturn(1);

    service.executerPurgerSalles();

    verify(jdbc, times(3)).update(contains("DELETE FROM salles"), anyMap());
    verify(jdbc)
        .update(
            contains("INSERT INTO fetch_jobs (type_job"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> Integer.valueOf(2).equals(p.get("nbMisAJour"))));
  }

  @Test
  void executerPurgerSalles_aucuneSalleOrpheline_neSupprimeRien() {
    when(jdbc.query(
            contains("SELECT id FROM salles"),
            anyMap(),
            org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
        .thenReturn(List.of());
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    service.executerPurgerSalles();

    verify(jdbc, never()).update(contains("DELETE FROM salles"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs (type_job"), anyMap());
  }

  @Test
  void executerPurgerArtistes_supprimeArtistesOrphelinsEtEnregistreStat() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(2);

    service.executerPurgerArtistes();

    verify(jdbc).update(contains("DELETE FROM artistes"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs (type_job"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs_historique"), anyMap());
  }

  @Test
  void executerPurgerLogsAdmin_supprimeTroisTablesEtEnregistreStat() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    service.executerPurgerLogsAdmin();

    verify(jdbc).update(contains("DELETE FROM logs_admin"), anyMap());
    verify(jdbc).update(contains("DELETE FROM fetch_jobs_historique"), anyMap());
    verify(jdbc).update(contains("DELETE FROM verifications"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs (type_job"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs_historique"), anyMap());
  }

  @Test
  void executerPurgerLogsAdmin_erreur_nePropagePas() {
    when(jdbc.update(anyString(), anyMap())).thenThrow(new RuntimeException("DB down"));

    assertDoesNotThrow(() -> service.executerPurgerLogsAdmin());
  }

  @Test
  void executerGererComptesInactifs_candidatTrouve_relanceEtProgrammeLaPurge() {
    when(jdbc.queryForList(contains("purge_le IS NULL"), anyMap()))
        .thenReturn(List.of(Map.of("id", "u1", "email", "a@test.fr")));
    when(purgeCompteEmailService.envoyerRelanceInactivite("a@test.fr", 15)).thenReturn(true);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    service.executerGererComptesInactifs();

    verify(purgeCompteEmailService).envoyerRelanceInactivite("a@test.fr", 15);
    verify(jdbc).update(contains("SET purge_le = now() + make_interval"), anyMap());
    verify(jdbc).update(contains("DELETE FROM users WHERE purge_le"), anyMap());
    verify(jdbc)
        .update(
            contains("INSERT INTO fetch_jobs (type_job"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> "gerer:comptes-inactifs".equals(p.get("typeJob"))));
  }

  @Test
  void executerGererComptesInactifs_aucunCandidat_neRelancePas() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    service.executerGererComptesInactifs();

    verify(purgeCompteEmailService, never())
        .envoyerRelanceInactivite(anyString(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void executerGererInvitesExpires_candidatTrouve_relanceAvecNouveauTokenEtPurge() {
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(java.util.Optional.of("https://strapontin.fr"));
    when(jdbc.queryForList(contains("must_change_credentials = true"), anyMap()))
        .thenReturn(List.of(Map.of("id", "u1", "email", "b@test.fr")));
    when(purgeCompteEmailService.envoyerRelanceInvitation(anyString(), anyString()))
        .thenReturn(true);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    service.executerGererInvitesExpires();

    verify(jdbc).update(contains("INSERT INTO verifications"), anyMap());
    verify(purgeCompteEmailService)
        .envoyerRelanceInvitation(
            org.mockito.ArgumentMatchers.eq("b@test.fr"),
            contains("https://strapontin.fr/reset-password?token="));
    verify(jdbc).update(contains("relance_invitation_envoyee_le = now()"), anyMap());
    verify(jdbc).update(contains("DELETE FROM users WHERE must_change_credentials"), anyMap());
  }

  @Test
  void executerGererInvitesExpires_aucunCandidat_neRelancePas() {
    when(configService.lireConfig("PUBLIC_BASE_URL")).thenReturn(java.util.Optional.empty());
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    service.executerGererInvitesExpires();

    verify(purgeCompteEmailService, never()).envoyerRelanceInvitation(anyString(), anyString());
  }
}
