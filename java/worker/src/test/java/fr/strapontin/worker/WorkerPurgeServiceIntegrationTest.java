/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.TicketmasterSource;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Sélection réelle des comptes relancés pour inactivité par {@link WorkerPurgeService}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class WorkerPurgeServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  // Le bean réel échoue au démarrage sans TICKETMASTER_API_KEY en base.
  @MockitoBean private TicketmasterSource ticketmasterSourceIgnore;
  @MockitoBean private EmailCompteService emailCompteService;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private WorkerPurgeService service;

  @Test
  void executerGererComptesInactifs_neRelanceQueLesComptesActivesInactifsDate() {
    creerCompte("inactif", ilYA(210), false);
    creerCompte("actif", ilYA(1), false);
    creerCompte("sansDate", null, false);
    creerCompte("invite", ilYA(210), true);
    when(emailCompteService.envoyerRelanceInactivite(anyString(), anyInt())).thenReturn(true);

    service.executerGererComptesInactifs();

    assertEquals(
        List.of("inactif"),
        jdbc.queryForList(
            "SELECT id FROM users WHERE purge_le IS NOT NULL ORDER BY id", Map.of(), String.class));
    assertEquals(
        List.of("info"),
        jdbc.queryForList(
            "SELECT niveau FROM logs_admin WHERE source = 'purge-comptes'",
            Map.of(),
            String.class));
  }

  @Test
  void executerGererComptesInactifs_vagueDeRelances_signaleEnWarn() {
    for (int i = 0; i < WorkerPurgeService.SEUIL_VAGUE_COMPTES; i++) {
      creerCompte("inactif" + i, ilYA(210), false);
    }
    when(emailCompteService.envoyerRelanceInactivite(anyString(), anyInt())).thenReturn(true);

    service.executerGererComptesInactifs();

    assertEquals(
        List.of("warn"),
        jdbc.queryForList(
            "SELECT niveau FROM logs_admin WHERE source = 'purge-comptes'",
            Map.of(),
            String.class));
  }

  // Un invité relancé garde son nouveau lien : il n'est supprimé qu'une fois tous ses liens
  // expirés, jamais le lendemain de la relance.
  @Test
  void executerGererInvitesExpires_inviteRelance_conserveJusquALExpirationDuNouveauLien() {
    creerInvite("relance", ilYA(8), null);
    creerInvite("expire", ilYA(20), ilYA(14));
    lienInvitation("expire@test.fr", ilYA(7));
    when(emailCompteService.envoyerRelanceInvitation(anyString(), anyString(), any()))
        .thenReturn(true);

    service.executerGererInvitesExpires();

    assertEquals(
        List.of("relance"),
        jdbc.queryForList("SELECT id FROM users ORDER BY id", Map.of(), String.class));
  }

  private void creerInvite(final String id, final Timestamp creeLe, final Timestamp relanceLe) {
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials, created_at,"
            + " relance_invitation_envoyee_le) VALUES (:id, :id, :email, true, :creeLe,"
            + " :relanceLe)",
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("email", id + "@test.fr")
            .addValue("creeLe", creeLe, Types.TIMESTAMP)
            .addValue("relanceLe", relanceLe, Types.TIMESTAMP));
  }

  private void lienInvitation(final String email, final Timestamp expireLe) {
    jdbc.update(
        "INSERT INTO verifications (id, identifier, value, expires_at, created_at, updated_at)"
            + " VALUES (gen_random_uuid()::text, :identifiant, 'x', :expireLe, now(), now())",
        new MapSqlParameterSource()
            .addValue("identifiant", "invite:" + email)
            .addValue("expireLe", expireLe, Types.TIMESTAMP));
  }

  @Test
  void executerPurgerSalles_salleOrphelineAvecLieux_traceLesLieuxSupprimes() {
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES ('orpheline', 'Orpheline',"
            + " 'orpheline')",
        Map.of());
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " SELECT id, 'eventim', 'internal:orpheline' FROM salles",
        Map.of());

    service.executerPurgerSalles();

    assertEquals(
        List.of("lieux supprimés avec leurs salles orphelines : 1"),
        jdbc.queryForList(
            "SELECT message FROM logs_admin WHERE source = 'purger-salles'",
            Map.of(),
            String.class));
  }

  private static Timestamp ilYA(final int jours) {
    return Timestamp.from(Instant.now().minus(Duration.ofDays(jours)));
  }

  private void creerCompte(
      final String id, final Timestamp derniereConnexion, final boolean invite) {
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials, derniere_connexion_le)"
            + " VALUES (:id, :id, :email, :invite, :derniereConnexion)",
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("email", id + "@test.fr")
            .addValue("invite", invite)
            .addValue("derniereConnexion", derniereConnexion, Types.TIMESTAMP));
  }

  private void evenementTresAbsent(final String artiste, final boolean maintenu) {
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, salle_norm, absences_consecutives,"
            + " maintenu_le) VALUES (:artiste, CURRENT_DATE + 30, 'salle', 60,"
            + " CASE WHEN :maintenu THEN now() END)",
        Map.of("artiste", artiste, "maintenu", maintenu));
  }

  // Rabah Asma : séance réelle que sa billetterie ne renvoie plus, conservée par un admin.
  @Test
  void executerPurgerConcertsNonConfirmes_seanceMaintenue_neEstPlusSignaleeASupprimer() {
    evenementTresAbsent("rabah asma", true);
    evenementTresAbsent("annule", false);

    service.executerPurgerConcertsNonConfirmes();

    assertEquals(
        List.of("annule"),
        jdbc.queryForList(
            "SELECT artiste_norm FROM evenements WHERE suppression_suggeree_le IS NOT NULL",
            Map.of(),
            String.class));
  }
}
