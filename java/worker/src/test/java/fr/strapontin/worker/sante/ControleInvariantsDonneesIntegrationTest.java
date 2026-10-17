/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
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
 * Invariants objectifs des données ({@link ControleInvariantsDonnees}) sur base réelle.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ControleInvariantsDonneesIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private SignalementMatchingService signalements;

  private ControleInvariantsDonnees controle;

  @BeforeEach
  void setUp() {
    controle = new ControleInvariantsDonnees(jdbc, signalements);
  }

  private UUID creerSalle() {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES ('l embarcadere', 'L''Embarcadère',"
            + " 'l embarcadere') RETURNING id",
        Map.of(),
        UUID.class);
  }

  private void evenement(final UUID salle, final String artiste, final String dept) {
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, dept)"
            + " VALUES (:artiste, CURRENT_DATE + 30, '20:00', 'l embarcadere',"
            + " CAST(:salle AS uuid), :dept)",
        Map.of("artiste", artiste, "salle", salle.toString(), "dept", dept));
  }

  private List<String> actifs() {
    return jdbc.queryForList(
        "SELECT type FROM signalements_matching WHERE resolu_le IS NULL ORDER BY type",
        Map.of(),
        String.class);
  }

  // L'Embarcadère : une seule source, concerts d'Aubervilliers (93) et de Boulogne-sur-Mer (62).
  @Test
  void verifier_salleSurDeuxDepartements_signaleEtRefermeUneFoisCorrigee() {
    final UUID salle = creerSalle();
    evenement(salle, "christophe willem", "62");
    evenement(salle, "autre artiste", "93");

    controle.verifier();
    assertEquals(List.of("salle_multi_departements"), actifs());

    jdbc.update("DELETE FROM evenements WHERE dept = '62'", Map.of());
    controle.verifier();
    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_salleContamineeSuivie_signaleLImpactUtilisateur() {
    final UUID salle = creerSalle();
    evenement(salle, "christophe willem", "62");
    evenement(salle, "autre artiste", "93");
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials)"
            + " VALUES ('u1', 'u1', 'u1@test.fr', true)",
        Map.of());
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_id) VALUES ('u1', CAST(:id AS uuid))",
        Map.of("id", salle.toString()));

    controle.verifier();
    controle.verifier();

    assertEquals(List.of("salle_multi_departements", "salle_suivie_contaminee"), actifs());
  }

  @Test
  void verifier_compteActiveSansIdentifiantDeConnexion_signaleUneIncoherence() {
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials)"
            + " VALUES ('u2', 'u2', 'u2@test.fr', false)",
        Map.of());

    controle.verifier();

    assertEquals(List.of("comptes_incoherents"), actifs());
  }

  // Élysée Montmartre réparti hors du Galabru, puis recréé sur le Galabru par son lieu
  // Ticketmaster resté en place.
  @Test
  void verifier_concertRecreeSurUneSalleDeclareeDistincte_signaleLeConcertRecent() {
    final UUID galabru = creerSalle();
    final UUID elysee =
        jdbc.queryForObject(
            "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES ('elysee', 'Élysée Montmartre',"
                + " 'elysee montmartre') RETURNING id",
            Map.of(),
            UUID.class);
    jdbc.update(
        "INSERT INTO salles_paires_distinctes (salle_a, salle_b) SELECT"
            + " LEAST(CAST(:a AS uuid), CAST(:b AS uuid)), GREATEST(CAST(:a AS uuid), CAST(:b AS"
            + " uuid))",
        Map.of("a", galabru.toString(), "b", elysee.toString()));
    evenement(elysee, "gaz coombes", "75");
    evenement(galabru, "gaz coombes", "75");

    controle.verifier();

    assertEquals(List.of("concert_double_salles_distinctes"), actifs());
  }

  private void evenementAvecSource(
      final UUID salle, final String artiste, final String heure, final String source) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, dept)"
                + " VALUES (:artiste, CURRENT_DATE + 30, CAST(:heure AS time), 'madeleine',"
                + " CAST(:salle AS uuid), '75') RETURNING id",
            Map.of("artiste", artiste, "heure", heure, "salle", salle.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut) VALUES"
            + " (CAST(:id AS uuid), CAST(:source AS source_concert), :url, :artiste, 'Madeleine')",
        Map.of(
            "id", id.toString(),
            "source", source,
            "url", "https://" + source + "/" + id,
            "artiste", artiste));
  }

  // L'église de la Madeleine : deux programmes Eventim pour un seul concert du soir.
  @Test
  void verifier_deuxFichesEventimAuMemeCreneau_signaleSansFusionner() {
    final UUID salle = creerSalle();
    evenementAvecSource(salle, "les 4 saisons de vivaldi", "20:00", "eventim");
    evenementAvecSource(salle, "vivaldi ave maria concerto adagios", "20:00", "eventim");

    controle.verifier();

    assertEquals(List.of("creneau_double_meme_source"), actifs());
    assertEquals(
        2L, jdbc.queryForObject("SELECT count(*) FROM evenements", Map.of(), Long.class));
  }

  @Test
  void verifier_deuxFichesDeSourcesDifferentesAuMemeCreneau_neSignalePasCeType() {
    final UUID salle = creerSalle();
    evenementAvecSource(salle, "spectacle a", "20:00", "eventim");
    evenementAvecSource(salle, "spectacle b", "20:00", "ticketmaster");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_troisFichesAuMemeCreneau_neSignalePas() {
    final UUID salle = creerSalle();
    evenementAvecSource(salle, "a", "20:00", "eventim");
    evenementAvecSource(salle, "b", "20:00", "eventim");
    evenementAvecSource(salle, "c", "20:00", "eventim");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_donneesSaines_aucunSignalement() {
    evenement(creerSalle(), "artiste", "75");

    assertEquals(0, controle.verifier());
    assertEquals(List.of(), actifs());
  }

  private void compteAvecMatch(
      final boolean emailActif, final String ageMatch, final String ageConcert) {
    jdbc.update(
        "INSERT INTO users (id, email, role, display_username, must_change_credentials)"
            + " VALUES ('u1', 'u1@test.fr', CAST('user' AS role_utilisateur), 'u1', true)",
        Map.of());
    jdbc.update(
        "INSERT INTO user_config (user_id, frequence_notifications, notif_artistes,"
            + " notif_artistes_push) VALUES ('u1', 'quotidien', :email, NOT :email)",
        Map.of("email", emailActif));
    final UUID artiste =
        jdbc.queryForObject(
            "INSERT INTO artistes (nom, nom_norm) VALUES ('A', 'a') RETURNING id",
            Map.of(),
            UUID.class);
    jdbc.update(
        "INSERT INTO user_artistes (user_id, artiste_affiche, artiste_id)"
            + " VALUES ('u1', 'A', CAST(:artiste AS uuid))",
        Map.of("artiste", artiste.toString()));
    final UUID concert =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, artiste_id, first_seen)"
                + " VALUES ('a', CURRENT_DATE + 30, 'salle', CAST(:artiste AS uuid),"
                + " now() - CAST(:age AS interval)) RETURNING id",
            Map.of("artiste", artiste.toString(), "age", ageConcert),
            UUID.class);
    jdbc.update(
        "INSERT INTO user_matches (user_id, concert_id, cree_le)"
            + " VALUES ('u1', CAST(:concert AS uuid), now() - CAST(:age AS interval))",
        Map.of("concert", concert.toString(), "age", ageMatch));
  }

  @Test
  void verifier_envoiEnAttenteAvecEmailActif_signaleAvecLaDateDuRapprochement() {
    compteAvecMatch(true, "3 days", "60 days");

    controle.verifier();

    assertEquals(List.of("notifications_non_envoyees"), actifs());
    assertEquals(
        LocalDate.now().minusDays(3).toString(),
        jdbc.queryForObject(
            "SELECT details->>'plusAncien' FROM signalements_matching"
                + " WHERE type = 'notifications_non_envoyees'",
            Map.of(),
            String.class));
  }

  // Concert connu de longue date, artiste suivi aujourd'hui : le délai n'est pas écoulé.
  @Test
  void verifier_artisteSuiviRecemment_aucunSignalement() {
    compteAvecMatch(true, "1 hour", "60 days");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  // Compte sans canal joignable : seul l'utilisateur peut agir, déjà averti.
  @Test
  void verifier_pushSansAppareil_aucunSignalement() {
    compteAvecMatch(false, "3 days", "60 days");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }
}
