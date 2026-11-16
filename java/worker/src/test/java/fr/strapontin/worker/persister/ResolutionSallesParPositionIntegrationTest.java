/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.strapontin.core.PositionLieu;
import fr.strapontin.sources.enrichissement.ClientNominatim;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Résolution des salles par position du lieu ({@link ResolveurSallePosition}) et simulation de
 * son effet sur les données existantes ({@link SimulationResolutionSalles}).
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ResolutionSallesParPositionIntegrationTest extends AbstractWorkerIntegrationTest {

  // Théâtre Montparnasse et Théâtre de Poche Montparnasse : mitoyens, ~40 m.
  private static final PositionLieu MONTPARNASSE = new PositionLieu(48.8432, 2.3236, "75014");
  private static final PositionLieu POCHE = new PositionLieu(48.8435, 2.3240, "75006");
  private static final PositionLieu VITTEL = new PositionLieu(48.2025, 5.9480, "88800");
  private static final PositionLieu PERPIGNAN = new PositionLieu(42.7025, 2.8946, "66000");

  @MockitoBean TicketmasterSource ticketmasterSource;
  @MockitoBean ClientNominatim clientNominatim;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private SimulationResolutionSalles simulation;

  private ResolveurSallePosition parPosition;

  @BeforeEach
  void setUp() {
    parPosition = new ResolveurSallePosition(jdbc);
  }

  private UUID creerSalle(final String nom, final String dept, final PositionLieu position) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept, latitude, longitude,"
            + " origine_position) VALUES (CAST(:id AS uuid), :cle, :nom, :nomNorm, :dept, :lat,"
            + " :lon, 'sources')",
        new MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("cle", nom.toLowerCase() + " " + dept)
            .addValue("nom", nom)
            .addValue("nomNorm", nom.toLowerCase())
            .addValue("dept", dept)
            .addValue("lat", position.latitude())
            .addValue("lon", position.longitude()));
    return id;
  }

  private void mapper(final UUID salle, final String identifiant, final PositionLieu position) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, latitude, longitude,"
            + " code_postal) VALUES (CAST(:id AS uuid), 'eventim', :identifiant, :lat, :lon, :cp)",
        new MapSqlParameterSource()
            .addValue("id", salle.toString())
            .addValue("identifiant", identifiant)
            .addValue("lat", position.latitude())
            .addValue("lon", position.longitude())
            .addValue("cp", position.codePostal()));
  }

  @Test
  void chercher_memeLieuNomDoublon_retientLaSalle() {
    final UUID montparnasse = creerSalle("Theatre Montparnasse", "75", MONTPARNASSE);

    final ResolveurSallePosition.SalleProche proche =
        parPosition.chercher("Théâtre Montparnasse", "75", MONTPARNASSE);

    assertEquals(montparnasse, proche.id());
  }

  @Test
  void chercher_salleMitoyenneNomDifferent_neLaRetientPas() {
    creerSalle("Theatre Montparnasse", "75", MONTPARNASSE);

    assertNull(parPosition.chercher("Théâtre de Poche Montparnasse", "75", POCHE));
  }

  @Test
  void chercher_homonymeDUneAutreVille_neLeRetientPas() {
    creerSalle("Palais des Congres", "88", VITTEL);

    assertNull(parPosition.chercher("Palais des Congrès", "66", PERPIGNAN));
  }

  @Test
  void incompatible_salleGeolocaliseeLoinDuLieu_vrai() {
    final UUID vittel = creerSalle("Palais des Congres", "88", VITTEL);

    assertTrue(parPosition.incompatible(vittel, "Palais des Congrès", PERPIGNAN));
    assertFalse(parPosition.incompatible(vittel, "Palais des Congrès", VITTEL));
    assertFalse(parPosition.incompatible(vittel, "Palais des Congrès", PositionLieu.INCONNUE));
  }

  @Test
  void incompatible_codePostalDifferentEtNomsDifferents_vrai() {
    final UUID dome = creerSalle("Dome de Paris", "75", MONTPARNASSE);
    jdbc.update(
        "UPDATE salles SET code_postal = '75015' WHERE id = CAST(:id AS uuid)",
        Map.of("id", dome.toString()));
    final PositionLieu vendomeClub = new PositionLieu(null, null, "75002");

    assertTrue(parPosition.incompatible(dome, "Vendôme Club Paris", vendomeClub));
  }

  @Test
  void incompatible_codePostalDifferentMaisNomIdentique_neVetoPas() {
    final UUID dome = creerSalle("dome de paris", "75", MONTPARNASSE);
    jdbc.update(
        "UPDATE salles SET code_postal = '75015' WHERE id = CAST(:id AS uuid)",
        Map.of("id", dome.toString()));

    assertFalse(
        parPosition.incompatible(dome, "Dome de Paris", new PositionLieu(null, null, "75002")));
  }

  @Test
  void incompatible_codePostalInconnuDUnCote_neVetoPas() {
    final UUID dome = creerSalle("Dome de Paris", "75", MONTPARNASSE);

    assertFalse(
        parPosition.incompatible(
            dome, "Vendôme Club Paris", new PositionLieu(null, null, "75002")));
  }

  // Le Minotaure (Vendôme) portait la position Wikidata d'un homonyme normand, sans aucun lieu
  // publié pour la confirmer : le veto créait une seconde salle « Le Minotaure Vendome ».
  @Test
  void incompatible_positionSalleNonConfirmee_neVetoPas() {
    final UUID minotaure =
        creerSalle("Le Minotaure", "41", new PositionLieu(49.29525, -0.30654, "14000"));
    final PositionLieu vendome = new PositionLieu(47.79186, 1.07223, "41100");

    jdbc.update(
        "UPDATE salles SET origine_position = 'wikidata' WHERE id = CAST(:id AS uuid)",
        Map.of("id", minotaure.toString()));
    assertFalse(parPosition.incompatible(minotaure, "Le Minotaure Vendome", vendome));

    jdbc.update(
        "UPDATE salles SET origine_position = 'geocodage' WHERE id = CAST(:id AS uuid)",
        Map.of("id", minotaure.toString()));
    assertFalse(parPosition.incompatible(minotaure, "Le Minotaure Vendome", vendome));
  }

  // Élysée Montmartre à 818 m d'une salle Galabru : seul le mot « montmartre » est commun.
  @Test
  void incompatible_lieuVoisinRapprocheParUnMotCommun_vrai() {
    final UUID galabru =
        creerSalle(
            "Theatre Montmartre Galabru Paris 18",
            "75",
            new PositionLieu(48.88732, 2.33453, "75018"));
    final PositionLieu elysee = new PositionLieu(48.88310, 2.34368, "75018");

    assertTrue(parPosition.incompatible(galabru, "ELYSEE MONTMARTRE PARIS 18", elysee));
    assertFalse(parPosition.incompatible(galabru, "THEATRE MONTMARTRE GALABRU", elysee));
  }

  @Test
  @SuppressWarnings("unchecked")
  void simuler_salleContamineeParUnLieuDistant_laClasseIncompatible() {
    final UUID vittel = creerSalle("Palais des Congres", "88", VITTEL);
    mapper(vittel, "internal:palais des congres@88800", VITTEL);
    mapper(vittel, "internal:palais des congres@66000", PERPIGNAN);

    final Map<String, Object> rapport = simulation.simuler();

    final Map<String, Integer> effectifs = (Map<String, Integer>) rapport.get("effectifs");
    assertEquals(1, effectifs.get("conforme"));
    assertEquals(1, effectifs.get("salleIncompatible"));
  }

  @Test
  void resolution_parPositionActivee_homonymeDistantEcarteEtSalleCreeeSansGeocodage() {
    final UUID vittel = creerSalle("Palais des Congres", "88", VITTEL);
    final ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
    resolveur.setResolutionParPosition(true);
    final ValeurConcert concert =
        FabriqueValeurConcert.nouveau()
            .artiste("Tina Arena")
            .salle("Palais des Congres")
            .dept("66")
            .source("eventim")
            .salleSourceId("eventim:internal:palais des congres@66000")
            .position(PERPIGNAN)
            .construire();

    final UUID salle = resolveur.upsertSalleSource(concert);

    assertNotEquals(vittel, salle);
    // Créée sans géocodage du nom : l'enregistrement des positions y posera celle de la source.
    verifyNoInteractions(clientNominatim);
  }

  private void poserCodePostal(final UUID salle, final String codePostal) {
    jdbc.update(
        "UPDATE salles SET code_postal = :cp WHERE id = CAST(:id AS uuid)",
        Map.of("cp", codePostal, "id", salle.toString()));
  }

  private UUID resoudreLieuEventim(final String salle, final String lieu, final String cp) {
    final ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
    resolveur.setResolutionParPosition(true);
    final ValeurConcert concert =
        FabriqueValeurConcert.nouveau()
            .artiste("Artiste de test")
            .salle(salle)
            .dept("75")
            .source("eventim")
            .salleSourceId("eventim:internal:" + lieu + "@" + cp)
            .position(new PositionLieu(null, null, cp))
            .construire();
    return resolveur.upsertSalleSource(concert);
  }

  // Le nom « vendome club paris » contient « dome » : sans le code postal (75002 contre 75015),
  // la fiche était rattachée au Dôme de Paris.
  @Test
  void resolution_lieuDeCodePostalDifferentEtNomContenant_neRattachePasLaSalleExistante() {
    final UUID dome = creerSalle("Dome de Paris", "75", MONTPARNASSE);
    poserCodePostal(dome, "75015");

    final UUID salle = resoudreLieuEventim("Vendôme Club Paris", "vendome club paris", "75002");

    assertNotEquals(dome, salle);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM salle_sources WHERE salle_id = CAST(:id AS uuid)",
            Map.of("id", dome.toString()),
            Integer.class));
  }

  // Le nom du lieu contient entièrement celui de la salle (« les etoiles »), les codes postaux
  // diffèrent (75011 contre 75010) : deux théâtres distincts.
  @Test
  void resolution_lieuDontLeNomContientCeluiDeLaSalle_etCodePostalDifferent_creeUneSalle() {
    final UUID etoiles = creerSalle("Les Etoiles", "75", MONTPARNASSE);
    poserCodePostal(etoiles, "75010");

    final UUID salle =
        resoudreLieuEventim("Le Passage vers les Etoiles", "le passage vers les etoiles", "75011");

    assertNotEquals(etoiles, salle);
  }

  @Test
  void tropEloignee_codePostalDifferentSansDistance_faux() {
    final UUID dome = creerSalle("Dome de Paris", "75", MONTPARNASSE);
    poserCodePostal(dome, "75015");
    final PositionLieu autreCodePostal = new PositionLieu(null, null, "75002");

    assertTrue(parPosition.incompatible(dome, "Vendôme Club Paris", autreCodePostal));
    assertFalse(parPosition.tropEloignee(dome, "Vendôme Club Paris", autreCodePostal));
  }

  @Test
  void tropEloignee_salleGeolocaliseeLoinDuLieu_vrai() {
    final UUID vittel = creerSalle("Palais des Congres", "88", VITTEL);

    assertTrue(parPosition.tropEloignee(vittel, "Palais des Congrès", PERPIGNAN));
  }

  // Une salle retenue sur artiste, date, heure et département reste retenue quand le lieu de la
  // billetterie, sans coordonnées, porte un autre code postal : aucune salle intermédiaire.
  @Test
  void resolution_cleForteEtCodePostalDifferent_rattacheLaSalleExistanteSansEnCreerUne() {
    final UUID salle = creerSalle("Centre des Congres - St Etienne", "42", MONTPARNASSE);
    poserCodePostal(salle, "42100");
    final LocalDate date = LocalDate.now().plusMonths(2);
    final UUID evenement =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, dept, salle_id)"
                + " VALUES ('artiste de test', :date, :heure, 'salle', '42', CAST(:salle AS uuid))"
                + " RETURNING id",
            new MapSqlParameterSource()
                .addValue("date", java.sql.Date.valueOf(date))
                .addValue("heure", java.sql.Time.valueOf(LocalTime.of(20, 30)))
                .addValue("salle", salle.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('ticketmaster' AS source_concert), 'x', 'y')",
        Map.of("id", evenement.toString()));

    final ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
    resolveur.setResolutionParPosition(true);
    final UUID resolue =
        resolveur.upsertSalleSource(
            FabriqueValeurConcert.nouveau()
                .artiste("Artiste de test")
                .date(date)
                .heure(LocalTime.of(20, 30))
                .salle("Palais municipal")
                .dept("42")
                .source("eventim")
                .salleSourceId("eventim:internal:palais municipal@42000")
                .position(new PositionLieu(null, null, "42000"))
                .construire());

    assertEquals(salle, resolue);
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM salles", Map.of(), Integer.class));
  }
}
