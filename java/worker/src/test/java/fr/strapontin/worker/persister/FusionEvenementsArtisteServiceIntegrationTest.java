/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
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
 * Tests d'intégration de {@link FusionEvenementsArtisteService} avec PostgreSQL réel
 * (Testcontainers).
 *
 * @since 3.0.0
 * @author Kwaite
 */
class FusionEvenementsArtisteServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  private static final String DATE_FUTURE = LocalDate.now().plusMonths(2).toString();

  // TICKETMASTER_API_KEY n'est pas seedée en base de test — sans ce mock, la construction du
  // contexte Spring échoue dès qu'une autre classe du contexte dépend de TicketmasterSource.
  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private FusionEvenementsArtisteService fusionEvenementsArtiste;

  @Autowired private FusionProduitsEventimService fusionProduitsEventim;
  @Autowired private FusionSeancesDeuxSourcesService fusionSeancesDeuxSources;

  @Autowired private NamedParameterJdbcTemplate jdbc;

  private UUID salle;

  @BeforeEach
  void nettoyerBase() {
    jdbc.update("DELETE FROM user_matches", Map.of());
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM logs_admin", Map.of());
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    salle = creerSalle("Zenith Test");
  }

  private UUID creerSalle(final String nom) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept)"
            + " VALUES (CAST(:id AS uuid), :cle, :nom, :nomNorm, '75')",
        new MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("cle", nom.toLowerCase())
            .addValue("nom", nom)
            .addValue("nomNorm", nom.toLowerCase()));
    return id;
  }

  private UUID creerArtiste(final String nom) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (CAST(:id AS uuid), :nom, :nomNorm)",
        Map.of("id", id.toString(), "nom", nom, "nomNorm", nom.toLowerCase() + " " + id));
    return id;
  }

  private UUID creerEvenement(final String artisteNorm, final UUID artisteId) {
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, artiste_id)"
                + " VALUES (:artisteNorm, CAST(:date AS date), CAST('20:30' AS time), 'zenith"
                + " test', CAST(:salleId AS uuid), CAST(:artisteId AS uuid)) RETURNING id",
            new MapSqlParameterSource()
                .addValue("artisteNorm", artisteNorm)
                .addValue("date", DATE_FUTURE)
                .addValue("salleId", salle.toString())
                .addValue("artisteId", artisteId == null ? null : artisteId.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('ticketmaster' AS source_concert), :artiste,"
            + " 'Zenith Test')",
        Map.of("id", evenementId.toString(), "artiste", artisteNorm));
    return evenementId;
  }

  // Cas réel documenté en production (#1240) : "elias j tv band" / "elias jtv band" normalisent
  // vers la même chaîne sans espaces ("eliasjtvband") — doublon flou couvert par
  // CorrespondanceArtiste.estDoublonArtiste, mais jamais réconcilié entre deux evenement_id déjà
  // persistés séparément (le dédoublonnage normal n'agit qu'à la création).
  @Test
  void fusionner_artisteNormConvergentApresCoup_fusionneLesDeuxEvenements() {
    creerEvenement("elias j tv band", null);
    creerEvenement("elias jtv band", null);

    final int fusions = fusionEvenementsArtiste.fusionner();

    assertEquals(1, fusions);
    final Long nbEvenements =
        jdbc.queryForObject("SELECT COUNT(*) FROM evenements", Map.of(), Long.class);
    assertEquals(1L, nbEvenements, "un seul événement doit subsister après fusion");
  }

  // Paire réelle de la prod : une billetterie publie la tête d'affiche, l'autre « + 1ère partie ».
  @Test
  void fusionner_memeAfficheAvecSuffixePremierePartie_fusionneLesDeuxEvenements() {
    creerEvenement("meryl", null);
    creerEvenement("meryl 1ere partie", null);

    assertEquals(1, fusionEvenementsArtiste.fusionner());
    assertEquals(
        "meryl",
        jdbc.queryForObject("SELECT artiste_norm FROM evenements", Map.of(), String.class));
  }

  // Deux têtes d'affiche suivies séparément gardent chacune leur événement.
  @Test
  void fusionner_coPlateauAvecArtistesRattaches_conserveLesDeuxEvenements() {
    creerEvenement("behemoth", creerArtiste("Behemoth"));
    creerEvenement("behemoth and dimmu borgir", creerArtiste("Dimmu Borgir"));

    assertEquals(0, fusionEvenementsArtiste.fusionner());
  }

  private UUID creerEvenementDansSalle(
      final UUID salleId, final String artisteNorm, final int absences, final String url) {
    return creerEvenementDansSalle(salleId, artisteNorm, absences, url, DATE_FUTURE);
  }

  private UUID creerEvenementDansSalle(
      final UUID salleId,
      final String artisteNorm,
      final int absences,
      final String url,
      final String date) {
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, dept,"
                + " absences_consecutives) VALUES (:artisteNorm, CAST(:date AS date),"
                + " CAST('20:00' AS time), 'salle', CAST(:salleId AS uuid), '75', :absences)"
                + " RETURNING id",
            new MapSqlParameterSource()
                .addValue("artisteNorm", artisteNorm)
                .addValue("date", date)
                .addValue("salleId", salleId.toString())
                .addValue("absences", absences),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('ticketmaster' AS source_concert), :url,"
            + " :artiste, 'salle')",
        Map.of("id", evenementId.toString(), "url", url, "artiste", artisteNorm));
    return evenementId;
  }

  // Father of Peace : publié à La Maroquinerie, republié à l'Alhambra sous un autre idmanif.
  @Test
  void fusionnerConcertsDeplaces_ancienneFicheAbsenteEtNouvelleVivante_fusionne() {
    final UUID maroquinerie = creerSalle("La Maroquinerie");
    final UUID alhambra = creerSalle("Alhambra");
    creerEvenementDansSalle(maroquinerie, "father of peace", 32, "https://tm/idmanif/663875");
    final UUID vivant =
        creerEvenementDansSalle(alhambra, "father of peace", 0, "https://tm/idmanif/668351");

    assertEquals(1, fusionEvenementsArtiste.fusionnerConcertsDeplaces());
    assertEquals(
        List.of(vivant),
        jdbc.queryForList(
            "SELECT id FROM evenements WHERE artiste_norm = 'father of peace'",
            Map.of(),
            UUID.class));
  }

  // Même intitulé générique, même soir, deux salles distinctes : la salle d'origine publie
  // encore d'autres séances de la même manifestation, la séance absente n'a pas été déplacée.
  @Test
  void fusionnerConcertsDeplaces_salleOriginePublieEncoreLaManifestation_neFusionnePas() {
    final UUID gaveau = creerSalle("Salle Gaveau");
    final UUID saintChapelle = creerSalle("Sainte-Chapelle");
    final String autreDate = LocalDate.now().plusMonths(3).toString();
    creerEvenementDansSalle(
        gaveau, "concert du nouvel an", 6, "https://tm/idmanif/660939/idseance/1");
    creerEvenementDansSalle(
        gaveau, "concert du nouvel an", 0, "https://tm/idmanif/660939/idseance/2", autreDate);
    creerEvenementDansSalle(
        saintChapelle, "concert du nouvel an", 0, "https://tm/idmanif/512345/idseance/9");

    assertEquals(0, fusionEvenementsArtiste.fusionnerConcertsDeplaces());
    assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM evenements", Map.of(), Long.class));
  }

  @Test
  void fusionnerConcertsDeplaces_aucunEvenementVivant_neFusionnePas() {
    final UUID maroquinerie = creerSalle("La Maroquinerie");
    final UUID alhambra = creerSalle("Alhambra");
    creerEvenementDansSalle(maroquinerie, "zebrahead", 4, "https://tm/idmanif/642731");
    creerEvenementDansSalle(alhambra, "zebrahead", 2, "https://tm/idmanif/668864");

    assertEquals(0, fusionEvenementsArtiste.fusionnerConcertsDeplaces());
  }

  // Second cas réel documenté (#1240) : "dexys midnight runners" / "dexy s midnight runners"
  // (apostrophe élidée par une source) — même mécanisme.
  @Test
  void fusionner_apostropheElidee_fusionneLesDeuxEvenements() {
    creerEvenement("dexys midnight runners", null);
    creerEvenement("dexy s midnight runners", null);

    final int fusions = fusionEvenementsArtiste.fusionner();

    assertEquals(1, fusions);
  }

  @Test
  void fusionner_artisteIdConnuSurUnSeulCote_estPreserveApresFusion() {
    final UUID artisteId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (CAST(:id AS uuid), 'Elias JTv Band',"
            + " 'elias jtv band')",
        Map.of("id", artisteId.toString()));
    creerEvenement("elias j tv band", null);
    creerEvenement("elias jtv band", artisteId);

    fusionEvenementsArtiste.fusionner();

    final String artisteIdApres =
        jdbc.queryForObject("SELECT artiste_id::text FROM evenements", Map.of(), String.class);
    assertEquals(artisteId.toString(), artisteIdApres);
  }

  // Les observations de sources différentes doivent toutes survivre côté cible — c'est tout
  // l'intérêt du modèle evenements/observations (#1192). Une observation de même source+url que
  // celle déjà côté cible est en revanche un vrai doublon (contrainte uq_observations) et doit
  // être supprimée, pas dupliquée.
  @Test
  void fusionner_observationsDeSourcesDifferentes_toutesReassigneesVersLaCible() {
    final UUID evtA = creerEvenement("elias j tv band", null);
    creerEvenement("elias jtv band", null);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('eventim' AS source_concert), 'Elias J.TV Band',"
            + " 'Zenith Test')",
        Map.of("id", evtA.toString()));

    fusionEvenementsArtiste.fusionner();

    final Long nbObservations =
        jdbc.queryForObject("SELECT COUNT(*) FROM observations", Map.of(), Long.class);
    assertEquals(
        2L,
        nbObservations,
        "les observations ticketmaster et eventim doivent toutes deux survivre côté cible");
  }

  @Test
  void fusionner_userMatchesSurLEvenementSource_reassignesVersLaCible() {
    final UUID evtA = creerEvenement("elias j tv band", null);
    creerEvenement("elias jtv band", null);
    jdbc.update(
        "INSERT INTO users (id, username, email) VALUES ('u1', 'u1', 'u1@test.fr')", Map.of());
    jdbc.update(
        "INSERT INTO user_matches (user_id, concert_id) VALUES ('u1', CAST(:id AS uuid))",
        Map.of("id", evtA.toString()));

    fusionEvenementsArtiste.fusionner();

    final Long nbMatches =
        jdbc.queryForObject("SELECT COUNT(*) FROM user_matches", Map.of(), Long.class);
    assertEquals(1L, nbMatches, "le match déjà notifié ne doit jamais être perdu par la fusion");
  }

  @Test
  void fusionner_fusionEffectuee_journaliseDansLogsAdmin() {
    creerEvenement("elias j tv band", null);
    creerEvenement("elias jtv band", null);

    fusionEvenementsArtiste.fusionner();

    final Long nbLogs =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM logs_admin WHERE source = 'reconciliation-nocturne' AND"
                + " message LIKE '%événements fusionnés%'",
            Map.of(), Long.class);
    assertEquals(1L, nbLogs);
  }

  @Test
  void fusionner_artistesReellementDistincts_neFusionnePas() {
    creerEvenement("the rolling stones", null);
    creerEvenement("the beatles", null);

    final int fusions = fusionEvenementsArtiste.fusionner();

    assertEquals(0, fusions);
    final Long nbEvenements =
        jdbc.queryForObject("SELECT COUNT(*) FROM evenements", Map.of(), Long.class);
    assertEquals(2L, nbEvenements);
  }

  @Test
  void fusionner_aucunEvenementPartageantSalleDateHeure_neFusionneRien() {
    creerEvenement("elias j tv band", null);
    final UUID autreSalle = creerSalle("Autre Salle");
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id)"
            + " VALUES ('elias jtv band', CAST(:date AS date), CAST('20:30' AS time), 'autre"
            + " salle', CAST(:salleId AS uuid))",
        Map.of("date", DATE_FUTURE, "salleId", autreSalle.toString()));

    final int fusions = fusionEvenementsArtiste.fusionner();

    assertEquals(0, fusions, "des salles différentes ne doivent jamais suffire à fusionner");
  }

  // --- fusionnerFichesPerimees ---

  private static final String URL_TM =
      "https://www.ticketmaster.fr/fr/manifestation/x/idmanif/613943";

  private UUID creerFiche(
      final UUID salleId,
      final String date,
      final String heure,
      final int absences,
      final String url) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id,"
                + " absences_consecutives) VALUES ('tina arena', CAST(:date AS date),"
                + " CAST(:heure AS time), 'palais des congres', CAST(:salleId AS uuid), :absences)"
                + " RETURNING id",
            new MapSqlParameterSource()
                .addValue("date", date)
                .addValue("heure", heure)
                .addValue("salleId", salleId == null ? null : salleId.toString())
                .addValue("absences", absences),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('ticketmaster' AS source_concert), :url,"
            + " 'Tina Arena', 'Palais des Congres')",
        Map.of("id", id.toString(), "url", url));
    return id;
  }

  private boolean existe(final UUID id) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM evenements WHERE id = CAST(:id AS uuid)",
            Map.of("id", id.toString()),
            Integer.class)
        == 1;
  }

  @Test
  void
      fusionnerFichesPerimees_ficheMalRattacheeFigeeEtFicheCorrecteVivante_fusionneDansLaVivante() {
    final UUID mauvaiseSalle = creerSalle("Palais des Congres Vittel");
    final UUID perimee = creerFiche(mauvaiseSalle, DATE_FUTURE, "20:00", 10, URL_TM);
    final UUID vivante = creerFiche(salle, DATE_FUTURE, "20:00", 0, URL_TM);
    jdbc.update(
        "INSERT INTO users (id, username, email) VALUES ('u1', 'u1', 'u1@test.fr')", Map.of());
    jdbc.update(
        "INSERT INTO user_matches (user_id, concert_id) VALUES ('u1', CAST(:id AS uuid))",
        Map.of("id", perimee.toString()));

    final int fusions = fusionEvenementsArtiste.fusionnerFichesPerimees();

    assertEquals(1, fusions);
    assertFalse(existe(perimee));
    assertTrue(existe(vivante));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM user_matches WHERE concert_id = CAST(:id AS uuid)",
            Map.of("id", vivante.toString()),
            Integer.class));
  }

  private void vueIlYA(final UUID evenementId, final int heures) {
    jdbc.update(
        "UPDATE observations SET first_seen = date_trunc('hour', now()) - make_interval(hours =>"
            + " :heures), updated_at = date_trunc('hour', now()) - make_interval(hours => :heures)"
            + " WHERE evenement_id = CAST(:id AS uuid)",
        Map.of("id", evenementId.toString(), "heures", heures));
  }

  @Test
  void fusionnerFichesPerimees_reportSousUneAutreUrlMemeIdmanifMemeDate_fusionne() {
    final UUID perimee = creerFiche(salle, DATE_FUTURE, "20:00", 10, URL_TM + "/idtier/18864121");
    vueIlYA(perimee, 72);
    final UUID vivante = creerFiche(salle, DATE_FUTURE, "19:30", 0, URL_TM + "/idseance/4350510");

    assertEquals(1, fusionEvenementsArtiste.fusionnerFichesPerimees());
    assertFalse(existe(perimee));
    assertTrue(existe(vivante));
  }

  private void passerEnEventim(final UUID evenementId) {
    jdbc.update(
        "UPDATE observations SET source = CAST('eventim' AS source_concert)"
            + " WHERE evenement_id = CAST(:id AS uuid)",
        Map.of("id", evenementId.toString()));
  }

  // Sting à Aluna : Ticketmaster publie la séance sans heure, Eventim à 16 h.
  @Test
  void fusionnerSeancesSansHeure_unePublicationSansHeureEtUneAvecHeure_fusionneDansLHorodatee() {
    final UUID sansHeure = creerFiche(salle, DATE_FUTURE, null, 0, URL_TM);
    final UUID horodatee = creerFiche(salle, DATE_FUTURE, "16:00", 0, URL_TM + "/idtier/1");
    passerEnEventim(horodatee);

    assertEquals(1, fusionSeancesDeuxSources.fusionnerSeancesSansHeure());
    assertFalse(existe(sansHeure));
    assertTrue(existe(horodatee));
  }

  // Sting à Aluna : Ticketmaster a cessé de renvoyer la fiche sans heure (43 absences).
  @Test
  void fusionnerSeancesSansHeure_ficheSansHeureDejaAbsente_fusionneDansLHorodatee() {
    final UUID sansHeure = creerFiche(salle, DATE_FUTURE, null, 43, URL_TM);
    final UUID horodatee = creerFiche(salle, DATE_FUTURE, "16:00", 0, URL_TM + "/idtier/1");
    passerEnEventim(horodatee);

    assertEquals(1, fusionSeancesDeuxSources.fusionnerSeancesSansHeure());
    assertFalse(existe(sansHeure));
    assertTrue(existe(horodatee));
  }

  @Test
  void fusionnerSeancesSansHeure_ficheHorodateeAbsente_neFusionnePas() {
    final UUID sansHeure = creerFiche(salle, DATE_FUTURE, null, 0, URL_TM);
    final UUID horodatee = creerFiche(salle, DATE_FUTURE, "16:00", 43, URL_TM + "/idtier/1");
    passerEnEventim(horodatee);

    assertEquals(0, fusionSeancesDeuxSources.fusionnerSeancesSansHeure());
    assertTrue(existe(sansHeure));
    assertTrue(existe(horodatee));
  }

  @Test
  void fusionnerSeancesSansHeure_memeSourceDesDeuxCotes_neFusionnePas() {
    final UUID sansHeure = creerFiche(salle, DATE_FUTURE, null, 0, URL_TM);
    final UUID horodatee = creerFiche(salle, DATE_FUTURE, "16:00", 0, URL_TM + "/idtier/1");

    assertEquals(0, fusionSeancesDeuxSources.fusionnerSeancesSansHeure());
    assertTrue(existe(sansHeure));
    assertTrue(existe(horodatee));
  }

  @Test
  void fusionnerSeancesSansHeure_deuxFichesHorodateesPossibles_ignoreLAmbiguite() {
    final UUID sansHeure = creerFiche(salle, DATE_FUTURE, null, 0, URL_TM);
    final UUID matinee = creerFiche(salle, DATE_FUTURE, "14:00", 0, URL_TM + "/idtier/1");
    final UUID soiree = creerFiche(salle, DATE_FUTURE, "20:00", 0, URL_TM + "/idtier/2");
    passerEnEventim(matinee);
    passerEnEventim(soiree);

    assertEquals(0, fusionSeancesDeuxSources.fusionnerSeancesSansHeure());
    assertTrue(existe(sansHeure));
  }

  // Toutes les séances d'une série partagent la même adresse : une séance absente ne rejoint pas
  // une séance vivante d'un autre jour.
  @Test
  void fusionnerFichesPerimees_memeUrlAutreDate_neFusionnePas() {
    final String lendemain = LocalDate.parse(DATE_FUTURE).plusDays(1).toString();
    final UUID perimee = creerFiche(salle, DATE_FUTURE, "16:30", 64, URL_TM);
    final UUID vivante = creerFiche(salle, lendemain, "20:30", 0, URL_TM);

    assertEquals(0, fusionEvenementsArtiste.fusionnerFichesPerimees());
    assertTrue(existe(perimee));
    assertTrue(existe(vivante));
  }

  @Test
  void fusionnerFichesPerimees_ficheMaintenueParUnAdministrateur_neFusionnePas() {
    final UUID maintenue = creerFiche(salle, DATE_FUTURE, "20:00", 64, URL_TM);
    jdbc.update(
        "UPDATE evenements SET maintenu_le = now() WHERE id = CAST(:id AS uuid)",
        Map.of("id", maintenue.toString()));
    final UUID vivante = creerFiche(salle, DATE_FUTURE, "22:30", 0, URL_TM);

    assertEquals(0, fusionEvenementsArtiste.fusionnerFichesPerimees());
    assertTrue(existe(maintenue));
    assertTrue(existe(vivante));
  }

  // Nubiyan Twist : l'ancienne fiche (20 h 30, d'une autre source) reste affichée à côté de la
  // nouvelle (19 h 30) tant qu'elle n'a pas atteint 50 absences.
  @Test
  void fusionnerSeancesHeureChangee_ancienneFicheAbsenteEtNouvelleVivante_fusionneDansLaVivante() {
    final UUID ancienne = creerFiche(salle, DATE_FUTURE, "20:30", 14, URL_TM + "/idseance/1");
    final UUID nouvelle = creerFiche(salle, DATE_FUTURE, "19:30", 0, "https://eventim.fr/p/2");

    assertEquals(1, fusionEvenementsArtiste.fusionnerSeancesHeureChangee());
    assertFalse(existe(ancienne));
    assertTrue(existe(nouvelle));
  }

  @Test
  void fusionnerSeancesHeureChangee_matineeEtSoiree_neFusionnePas() {
    final UUID soir = creerFiche(salle, DATE_FUTURE, "18:00", 10, URL_TM + "/idseance/1");
    final UUID matinee = creerFiche(salle, DATE_FUTURE, "13:00", 0, URL_TM + "/idseance/2");

    assertEquals(0, fusionEvenementsArtiste.fusionnerSeancesHeureChangee());
    assertTrue(existe(soir));
    assertTrue(existe(matinee));
  }

  @Test
  void fusionnerSeancesHeureChangee_deuxFichesVivantesProches_ignoreLAmbiguite() {
    final UUID ancienne = creerFiche(salle, DATE_FUTURE, "20:30", 14, URL_TM + "/idseance/1");
    creerFiche(salle, DATE_FUTURE, "19:30", 0, URL_TM + "/idseance/2");
    creerFiche(salle, DATE_FUTURE, "20:00", 0, URL_TM + "/idseance/3");

    assertEquals(0, fusionEvenementsArtiste.fusionnerSeancesHeureChangee());
    assertTrue(existe(ancienne));
  }

  // Le Roi Lion à Mogador : séances de 13 h et 18 h sous un même idmanif, vues
  // au même passage ; une absence de l'une ne fait pas d'elle un report.
  @Test
  void fusionnerFichesPerimees_deuxSeancesVuesEnsembleMemeIdmanif_neFusionnePas() {
    final UUID soir = creerFiche(salle, DATE_FUTURE, "18:00", 10, URL_TM + "/idseance/4424739");
    final UUID matinee = creerFiche(salle, DATE_FUTURE, "13:00", 0, URL_TM + "/idseance/4424738");
    vueIlYA(soir, 72);
    vueIlYA(matinee, 72);

    assertEquals(0, fusionEvenementsArtiste.fusionnerFichesPerimees());
    assertTrue(existe(soir));
    assertTrue(existe(matinee));
  }

  @Test
  void fusionnerFichesPerimees_deuxFichesVivantes_neFusionnePas() {
    final UUID a = creerFiche(salle, DATE_FUTURE, "15:00", 0, URL_TM + "/idseance/1");
    final UUID b = creerFiche(salle, DATE_FUTURE, "20:00", 0, URL_TM + "/idseance/2");

    assertEquals(0, fusionEvenementsArtiste.fusionnerFichesPerimees());
    assertTrue(existe(a));
    assertTrue(existe(b));
  }

  @Test
  void fusionnerFichesPerimees_memeIdmanifAutreDateSansUrlCommune_neFusionnePas() {
    final String autreDate = LocalDate.now().plusMonths(3).toString();
    final UUID perimee = creerFiche(salle, DATE_FUTURE, "20:00", 10, URL_TM + "/idseance/1");
    creerFiche(salle, autreDate, "20:00", 0, URL_TM + "/idseance/2");

    assertEquals(0, fusionEvenementsArtiste.fusionnerFichesPerimees());
    assertTrue(existe(perimee));
  }

  @Test
  void fusionnerFichesPerimees_absencesSousLeSeuil_neFusionnePas() {
    final UUID recente =
        creerFiche(
            salle,
            DATE_FUTURE,
            "20:00",
            FusionEvenementsArtisteService.SEUIL_ABSENCES_FICHE_PERIMEE - 1,
            URL_TM);
    creerFiche(creerSalle("Autre"), DATE_FUTURE, "20:00", 0, URL_TM);

    assertEquals(0, fusionEvenementsArtiste.fusionnerFichesPerimees());
    assertTrue(existe(recente));
  }

  // --- fusionnerMemeCreneauDeuxSources ---

  private UUID evenementSource(final UUID salleId, final String titre, final String source) {
    return evenementSource(salleId, titre, source, DATE_FUTURE);
  }

  private UUID evenementSource(
      final UUID salleId, final String titre, final String source, final String date) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id) VALUES"
                + " (:titre, CAST(:date AS date), CAST('19:30' AS time), 'opera bastille',"
                + " CAST(:salleId AS uuid)) RETURNING id",
            Map.of("titre", titre, "date", date, "salleId", salleId.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut) VALUES"
            + " (CAST(:id AS uuid), CAST(:source AS source_concert), :url, :titre, 'Opera"
            + " Bastille')",
        Map.of(
            "id",
            id.toString(),
            "source",
            source,
            "url",
            "https://" + source + "/" + id,
            "titre",
            titre));
    return id;
  }

  private UUID seanceSource(
      final UUID salleId, final String artiste, final String heure, final String source) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id) VALUES"
                + " (:artiste, CAST(:date AS date), CAST(:heure AS time), 'new morning',"
                + " CAST(:salleId AS uuid)) RETURNING id",
            Map.of(
                "artiste", artiste,
                "date", DATE_FUTURE,
                "heure", heure,
                "salleId", salleId.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut) VALUES"
            + " (CAST(:id AS uuid), CAST(:source AS source_concert), :url, :artiste, 'New"
            + " Morning')",
        Map.of(
            "id",
            id.toString(),
            "source",
            source,
            "url",
            "https://" + source + "/" + id,
            "artiste",
            artiste));
    return id;
  }

  // Nubiyan Twist au New Morning : 20 h 30 chez Ticketmaster, 19 h 30 chez Eventim.
  @Test
  void fusionnerMemeSeanceDeuxSourcesHeuresProches_ecartDUneHeure_fusionneDansLaFicheTm() {
    final UUID newMorning = creerSalle("New Morning");
    final UUID tm = seanceSource(newMorning, "nubiyan twist", "20:30", "ticketmaster");
    final UUID ev = seanceSource(newMorning, "nubiyan twist", "19:30", "eventim");

    assertEquals(1, fusionEvenementsArtiste.fusionnerMemeSeanceDeuxSourcesHeuresProches());
    assertTrue(existe(tm));
    assertFalse(existe(ev));
  }

  // Mark Guiliana au New Morning : une fiche périmée (20 h) et une seconde séance (22 h) ne
  // bloquent plus le rapprochement de 19 h 30 (Eventim) et 19 h 45 (Ticketmaster).
  @Test
  void fusionnerMemeSeanceDeuxSourcesHeuresProches_fichePerimeeEtSecondeSeance_fusionneLesDeux() {
    final UUID newMorning = creerSalle("New Morning");
    final UUID evenementEventim = seanceSource(newMorning, "mark guiliana", "19:30", "eventim");
    final UUID tm = seanceSource(newMorning, "mark guiliana", "19:45", "ticketmaster");
    final UUID perimee = seanceSource(newMorning, "mark guiliana", "20:00", "ticketmaster");
    final UUID soiree = seanceSource(newMorning, "mark guiliana", "22:00", "ticketmaster");
    jdbc.update(
        "UPDATE evenements SET absences_consecutives = 8 WHERE id = CAST(:id AS uuid)",
        Map.of("id", perimee.toString()));

    assertEquals(1, fusionEvenementsArtiste.fusionnerMemeSeanceDeuxSourcesHeuresProches());
    assertTrue(existe(tm));
    assertFalse(existe(evenementEventim));
    assertTrue(existe(soiree));

    assertEquals(1, fusionEvenementsArtiste.fusionnerSeancesHeureChangee());
    assertFalse(existe(perimee));
    assertTrue(existe(tm));
    assertTrue(existe(soiree));
  }

  // Zimmer90 à l'Élysée Montmartre : 18 h 30 (ouverture des portes) chez Eventim, 20 h chez
  // Ticketmaster.
  @Test
  void fusionnerMemeSeanceDeuxSourcesHeuresProches_ouvertureDesPortesEtSpectacle_fusionne() {
    final UUID elysee = creerSalle("Élysée Montmartre");
    final UUID tm = seanceSource(elysee, "zimmer90", "20:00", "ticketmaster");
    final UUID ev = seanceSource(elysee, "zimmer90", "18:30", "eventim");

    assertEquals(1, fusionEvenementsArtiste.fusionnerMemeSeanceDeuxSourcesHeuresProches());
    assertTrue(existe(tm));
    assertFalse(existe(ev));
  }

  @Test
  void fusionnerMemeSeanceDeuxSourcesHeuresProches_ecartDeDeuxHeuresEtDemie_neFusionnePas() {
    final UUID newMorning = creerSalle("New Morning");
    seanceSource(newMorning, "nubiyan twist", "17:30", "eventim");
    seanceSource(newMorning, "nubiyan twist", "20:00", "ticketmaster");

    assertEquals(0, fusionEvenementsArtiste.fusionnerMemeSeanceDeuxSourcesHeuresProches());
  }

  @Test
  void fusionnerMemeSeanceDeuxSourcesHeuresProches_matineeEtSoiree_neFusionnePas() {
    final UUID newMorning = creerSalle("New Morning");
    seanceSource(newMorning, "nubiyan twist", "15:00", "ticketmaster");
    seanceSource(newMorning, "nubiyan twist", "20:00", "eventim");

    assertEquals(0, fusionEvenementsArtiste.fusionnerMemeSeanceDeuxSourcesHeuresProches());
  }

  @Test
  void fusionnerMemeSeanceDeuxSourcesHeuresProches_memeSourceDesDeuxCotes_neFusionnePas() {
    final UUID newMorning = creerSalle("New Morning");
    seanceSource(newMorning, "nubiyan twist", "19:30", "eventim");
    seanceSource(newMorning, "nubiyan twist", "20:00", "eventim");

    assertEquals(0, fusionEvenementsArtiste.fusionnerMemeSeanceDeuxSourcesHeuresProches());
  }

  // Lady Macbeth de Mzensk : « of Mtsensk » chez Ticketmaster, « de Mzensk » chez Eventim.
  @Test
  void fusionnerMemeCreneauDeuxSources_memeSpectacleDeuxIntitules_fusionne() {
    final UUID bastille = creerSalle("Opera Bastille");
    final UUID tm = evenementSource(bastille, "lady macbeth of mtsensk", "ticketmaster");
    final UUID ev = evenementSource(bastille, "lady macbeth de mzensk", "eventim");

    assertEquals(1, fusionSeancesDeuxSources.fusionnerMemeCreneauDeuxSources());
    assertTrue(existe(tm));
    assertFalse(existe(ev));
  }

  // Église de la Madeleine : le même programme sous deux intitulés Eventim au même instant.
  @Test
  void fusionnerVariantesDeTitreEventim_deuxIntitulesDuMemeProgramme_fusionne() {
    final UUID madeleine = creerSalle("Eglise de la Madeleine");
    final UUID a =
        evenementSource(
            madeleine, "les 4 saisons de vivaldi ave maria et celebres adagios", "eventim");
    final UUID b =
        evenementSource(madeleine, "extraits ave maria les 4 saisons de vivaldi", "eventim");

    assertEquals(1, fusionSeancesDeuxSources.fusionnerVariantesDeTitreEventim());
    assertTrue(existe(a) != existe(b));
  }

  @Test
  void fusionnerVariantesDeTitreEventim_deuxProgrammesDifferents_neFusionnePas() {
    final UUID madeleine = creerSalle("Eglise de la Madeleine");
    evenementSource(madeleine, "requiem de mozart", "eventim");
    evenementSource(madeleine, "gospel et negro spirituals", "eventim");

    assertEquals(0, fusionSeancesDeuxSources.fusionnerVariantesDeTitreEventim());
  }

  // Essaïon : trois spectacles distincts partagent « père » et « noël ».
  @Test
  void fusionnerVariantesDeTitreEventim_spectaclesDeNoelDistincts_neFusionnePas() {
    final UUID essaion = creerSalle("Essaion");
    final UUID charlotte =
        evenementSource(essaion, "charlotte et le costume du pere noel", "eventim");
    final UUID lumiere = evenementSource(essaion, "le pere noel aime la lumiere", "eventim");

    assertEquals(0, fusionSeancesDeuxSources.fusionnerVariantesDeTitreEventim());
    assertTrue(existe(charlotte));
    assertTrue(existe(lumiere));
  }

  // Un théâtre à plusieurs scènes cumule les créneaux à deux fiches : ni une adaptation de titre
  // sans ressemblance (Marlaguette / La flûte enchantée), ni rien d'autre ne les réunit.
  @Test
  void fusionnerMemeCreneauDeuxSources_salleAPlusieursScenesTitresSansRessemblance_neFusionnePas() {
    final UUID essaion = creerSalle("Essaion");
    for (int jour = 1;
        jour <= FusionSeancesDeuxSourcesService.SEUIL_CRENEAUX_SALLE_MULTIPLE - 1;
        jour++) {
      final String date = LocalDate.parse(DATE_FUTURE).plusDays(jour).toString();
      evenementSource(essaion, "spectacle un " + jour, "eventim", date);
      evenementSource(essaion, "spectacle deux " + jour, "eventim", date);
    }
    final UUID tm = evenementSource(essaion, "la flute enchantee", "ticketmaster");
    final UUID ev = evenementSource(essaion, "marlaguette", "eventim");

    assertEquals(0, fusionSeancesDeuxSources.fusionnerMemeCreneauDeuxSources());
    assertTrue(existe(tm));
    assertTrue(existe(ev));
  }

  // Zénith Paris - La Villette : le spectacle annulé n'absorbe pas son remplaçant du même soir.
  @Test
  void fusionnerMemeCreneauDeuxSources_ficheAnnulee_neFusionnePasLeRemplacant() {
    final UUID zenith = creerSalle("Zenith Paris La Villette");
    final UUID annule = evenementSource(zenith, "krys", "ticketmaster");
    final UUID remplacant = evenementSource(zenith, "habib fatako", "eventim");
    jdbc.update(
        "UPDATE evenements SET statut = 'cancelled' WHERE id = CAST(:id AS uuid)",
        Map.of("id", annule.toString()));

    assertEquals(0, fusionSeancesDeuxSources.fusionnerMemeCreneauDeuxSources());
    assertTrue(existe(annule));
    assertTrue(existe(remplacant));
  }

  // Théâtre Montparnasse : « Bouvard et Pécuchet » vient du Poche-Montparnasse, mal rattaché.
  @Test
  void fusionnerMemeCreneauDeuxSources_salleSignaleeEnDispersion_neFusionnePas() {
    final UUID salle = creerSalle("Theatre Montparnasse");
    evenementSource(salle, "suzanne", "ticketmaster");
    evenementSource(salle, "bouvard et pecuchet", "eventim");
    jdbc.update(
        "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance) VALUES"
            + " ('dispersion_sources_salle', 'salle', CAST(:id AS uuid), 'haute')",
        Map.of("id", salle.toString()));

    assertEquals(0, fusionSeancesDeuxSources.fusionnerMemeCreneauDeuxSources());
  }

  @Test
  void fusionnerMemeCreneauDeuxSources_troisEvenementsAuCreneau_neFusionnePas() {
    final UUID salle = creerSalle("Salle Gaveau");
    evenementSource(salle, "antonio vivaldi", "ticketmaster");
    evenementSource(salle, "bebe concert", "eventim");
    evenementSource(salle, "gloria de vivaldi", "eventim");

    assertEquals(0, fusionSeancesDeuxSources.fusionnerMemeCreneauDeuxSources());
  }

  // --- fusionnerMemeProduitEventim ---

  // Chaque observation : { source, url, âge en heures de la dernière vue }.
  private UUID ficheAvecObservations(
      final UUID salleId,
      final String artisteNorm,
      final String heure,
      final String[]... observations) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id) VALUES"
                + " (:artiste, CAST(:date AS date), CAST(:heure AS time), 'la seine musicale',"
                + " CAST(:salleId AS uuid)) RETURNING id",
            Map.of(
                "artiste",
                artisteNorm,
                "date",
                DATE_FUTURE,
                "heure",
                heure,
                "salleId",
                salleId.toString()),
            UUID.class);
    for (final String[] o : observations) {
      jdbc.update(
          "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut,"
              + " updated_at) VALUES (CAST(:id AS uuid), CAST(:source AS source_concert), :url,"
              + " :artiste, 'La Seine Musicale', now() - make_interval(hours => :age))",
          Map.of(
              "id",
              id.toString(),
              "source",
              o[0],
              "url",
              o[1],
              "artiste",
              artisteNorm,
              "age",
              Integer.parseInt(o[2])));
    }
    return id;
  }

  // Salle Gaveau : le produit de 10h est resté sur la fiche de 11h30, qui porte son propre produit.
  @Test
  void retirerObservationsDeProduitReattribue_produitRepublieSurUneAutreFiche_leRetire() {
    final UUID salle = creerSalle("Salle Gaveau");
    final UUID matin =
        ficheAvecObservations(
            salle,
            "le petit prince",
            "10:00",
            new String[] {"eventim", "https://www.eventim.fr/event/petit-prince-21738765", "1"});
    final UUID midi =
        ficheAvecObservations(
            salle,
            "le petit prince",
            "11:30",
            new String[] {"eventim", "https://www.eventim.fr/event/petit-prince-21738765", "100"},
            new String[] {"eventim", "https://www.eventim.fr/event/petit-prince-21738766", "1"});

    assertEquals(1, fusionProduitsEventim.retirerObservationsDeProduitReattribue());

    assertEquals(1L, nombreObservations(matin, "eventim"));
    assertEquals(1L, nombreObservations(midi, "eventim"));
  }

  @Test
  void retirerObservationsDeProduitReattribue_ficheSeuleSurSonProduit_neRetireRien() {
    final UUID salle = creerSalle("Salle Gaveau");
    ficheAvecObservations(
        salle,
        "le petit prince",
        "10:00",
        new String[] {"eventim", "https://www.eventim.fr/event/petit-prince-21738765", "100"});

    assertEquals(0, fusionProduitsEventim.retirerObservationsDeProduitReattribue());
  }

  private long nombreObservations(final UUID evenementId, final String source) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM observations WHERE evenement_id = CAST(:id AS uuid)"
            + " AND source = CAST(:source AS source_concert)",
        Map.of("id", evenementId.toString(), "source", source),
        Long.class);
  }

  // Béjart Ballet Lausanne : le titre Eventim change, l'identifiant du produit reste.
  @Test
  void fusionnerProduit_titreModifie_fusionneEtGardeLaFicheLaMieuxPortee() {
    final UUID ancienne =
        ficheAvecObservations(
            salle,
            "bejart ballet lausanne",
            "20:30",
            new String[] {"ticketmaster", "https://tm/idmanif/1", "1"},
            new String[] {"eventim", "https://www.eventim.fr/event/bejart-ballet-22173044", "250"});
    final UUID recente =
        ficheAvecObservations(
            salle,
            "bolero",
            "20:30",
            new String[] {"eventim", "https://www.eventim.fr/event/bolero-22173044", "1"});

    assertEquals(1, fusionProduitsEventim.fusionner());
    assertTrue(existe(ancienne));
    assertFalse(existe(recente));
    assertEquals(1L, nombreObservations(ancienne, "eventim"));
    assertEquals(
        "https://www.eventim.fr/event/bolero-22173044",
        jdbc.queryForObject(
            "SELECT url FROM observations WHERE evenement_id = CAST(:id AS uuid)"
                + " AND source = 'eventim'",
            Map.of("id", ancienne.toString()),
            String.class));
  }

  // Celtic Ovation : la première publication portait une faute de frappe.
  @Test
  void fusionnerProduit_premierePublicationFautive_gardeLIntituleLePlusRecent() {
    ficheAvecObservations(
        salle,
        "celtic avation",
        "20:30",
        new String[] {"eventim", "https://www.eventim.fr/event/celtic-avation-22300001", "250"});
    ficheAvecObservations(
        salle,
        "celtic ovation",
        "20:30",
        new String[] {"eventim", "https://www.eventim.fr/event/celtic-ovation-22300001", "1"});

    assertEquals(1, fusionProduitsEventim.fusionner());
    assertEquals(
        List.of("celtic ovation"),
        jdbc.queryForList("SELECT artiste_norm FROM evenements", Map.of(), String.class));
  }

  private void deplacerEtCompterAbsences(
      final UUID id, final int joursDecalage, final int absences) {
    jdbc.update(
        "UPDATE evenements SET date = date + :jours, absences_consecutives = :absences"
            + " WHERE id = CAST(:id AS uuid)",
        Map.of("id", id.toString(), "jours", joursDecalage, "absences", absences));
  }

  // Bachar Mar-Khalifé : le produit passe du 05/11 au 08/11, l'ancienne fiche reste à l'affiche.
  @Test
  void fusionnerProduitsRepublies_produitRepublieAUneAutreDate_ancienneFicheRejointLaNouvelle() {
    final String url = "https://www.eventim.fr/event/christophe-21737681";
    final UUID ancienne =
        ficheAvecObservations(salle, "bachar", "20:30", new String[] {"eventim", url, "100"});
    final UUID nouvelle =
        ficheAvecObservations(salle, "bachar", "16:30", new String[] {"eventim", url, "1"});
    deplacerEtCompterAbsences(ancienne, -3, 3);

    assertEquals(1, fusionProduitsEventim.fusionnerProduitsRepublies());
    assertFalse(existe(ancienne));
    assertTrue(existe(nouvelle));
  }

  // Vincent Dedienne : même jour, l'heure passe de 19 h à 21 h.
  @Test
  void fusionnerProduitsRepublies_memeJourHeureTropEloignee_ancienneFicheRejointLaNouvelle() {
    final String url = "https://www.eventim.fr/event/dedienne-21898719";
    final UUID ancienne =
        ficheAvecObservations(salle, "dedienne", "19:00", new String[] {"eventim", url, "100"});
    final UUID nouvelle =
        ficheAvecObservations(salle, "dedienne", "21:00", new String[] {"eventim", url, "1"});
    deplacerEtCompterAbsences(ancienne, 0, 5);

    assertEquals(1, fusionProduitsEventim.fusionnerProduitsRepublies());
    assertFalse(existe(ancienne));
    assertTrue(existe(nouvelle));
  }

  // Brooke Combe : le produit de Supersonic Records disparaît, un autre paraît à La Bellevilloise.
  @Test
  void
      fusionnerProduitsRepubliesAilleurs_produitRepublieDansUneAutreSalle_ancienneFicheRejointLaNouvelle() {
    final UUID supersonic = creerSalle("Supersonic Records");
    final UUID ancienne =
        ficheAvecObservations(
            supersonic,
            "brooke combe",
            "20:30",
            new String[] {"eventim", "https://www.eventim.fr/event/brooke-22287729", "100"});
    final UUID nouvelle =
        ficheAvecObservations(
            salle,
            "brooke combe",
            "20:00",
            new String[] {"ticketmaster", "https://tm/idmanif/5", "1"},
            new String[] {"eventim", "https://www.eventim.fr/event/brooke-22350946", "1"});

    assertEquals(1, fusionProduitsEventim.fusionnerProduitsRepubliesAilleurs());
    assertFalse(existe(ancienne));
    assertTrue(existe(nouvelle));
  }

  @Test
  void fusionnerProduitsRepubliesAilleurs_ancienneFicheEncoreVue_neFusionnePas() {
    final UUID supersonic = creerSalle("Supersonic Records");
    final UUID ancienne =
        ficheAvecObservations(
            supersonic,
            "brooke combe",
            "20:30",
            new String[] {"eventim", "https://www.eventim.fr/event/brooke-22287729", "2"});
    final UUID nouvelle =
        ficheAvecObservations(
            salle,
            "brooke combe",
            "20:00",
            new String[] {"eventim", "https://www.eventim.fr/event/brooke-22350946", "1"});

    assertEquals(0, fusionProduitsEventim.fusionnerProduitsRepubliesAilleurs());
    assertTrue(existe(ancienne));
    assertTrue(existe(nouvelle));
  }

  @Test
  void fusionnerProduitsRepubliesAilleurs_ancienneFicheEntretenueParTicketmaster_neFusionnePas() {
    final UUID supersonic = creerSalle("Supersonic Records");
    final UUID ancienne =
        ficheAvecObservations(
            supersonic,
            "brooke combe",
            "20:30",
            new String[] {"eventim", "https://www.eventim.fr/event/brooke-22287729", "100"},
            new String[] {"ticketmaster", "https://tm/idmanif/9", "1"});
    final UUID nouvelle =
        ficheAvecObservations(
            salle,
            "brooke combe",
            "20:00",
            new String[] {"eventim", "https://www.eventim.fr/event/brooke-22350946", "1"});

    assertEquals(0, fusionProduitsEventim.fusionnerProduitsRepubliesAilleurs());
    assertTrue(existe(ancienne));
    assertTrue(existe(nouvelle));
  }

  @Test
  void fusionnerProduitsRepubliesAilleurs_ancienneFicheMaintenue_neFusionnePas() {
    final UUID supersonic = creerSalle("Supersonic Records");
    final UUID ancienne =
        ficheAvecObservations(
            supersonic,
            "brooke combe",
            "20:30",
            new String[] {"eventim", "https://www.eventim.fr/event/brooke-22287729", "100"});
    ficheAvecObservations(
        salle,
        "brooke combe",
        "20:00",
        new String[] {"eventim", "https://www.eventim.fr/event/brooke-22350946", "1"});
    jdbc.update(
        "UPDATE evenements SET maintenu_le = now() WHERE id = CAST(:id AS uuid)",
        Map.of("id", ancienne.toString()));

    assertEquals(0, fusionProduitsEventim.fusionnerProduitsRepubliesAilleurs());
    assertTrue(existe(ancienne));
  }

  @Test
  void fusionnerProduitsRepublies_deuxFichesVivantes_neFusionnePas() {
    final String url = "https://www.eventim.fr/event/serie-21000001";
    final UUID a =
        ficheAvecObservations(salle, "serie", "20:00", new String[] {"eventim", url, "1"});
    final UUID b =
        ficheAvecObservations(salle, "serie", "20:30", new String[] {"eventim", url, "1"});
    deplacerEtCompterAbsences(b, 7, 0);

    assertEquals(0, fusionProduitsEventim.fusionnerProduitsRepublies());
    assertTrue(existe(a));
    assertTrue(existe(b));
  }

  @Test
  void fusionnerProduitsRepublies_ancienneFicheEntretenueParTicketmaster_neFusionnePas() {
    final String url = "https://www.eventim.fr/event/tm-21000002";
    final UUID ancienne =
        ficheAvecObservations(
            salle,
            "tm",
            "20:00",
            new String[] {"eventim", url, "100"},
            new String[] {"ticketmaster", "https://tm/idmanif/9", "1"});
    final UUID nouvelle =
        ficheAvecObservations(salle, "tm", "21:30", new String[] {"eventim", url, "1"});
    deplacerEtCompterAbsences(ancienne, -2, 5);

    assertEquals(0, fusionProduitsEventim.fusionnerProduitsRepublies());
    assertTrue(existe(ancienne));
    assertTrue(existe(nouvelle));
  }

  @Test
  void fusionnerProduitsRepublies_ancienneFicheMaintenue_neFusionnePas() {
    final String url = "https://www.eventim.fr/event/maintenue-21000003";
    final UUID ancienne =
        ficheAvecObservations(salle, "maintenue", "20:00", new String[] {"eventim", url, "100"});
    final UUID nouvelle =
        ficheAvecObservations(salle, "maintenue", "21:30", new String[] {"eventim", url, "1"});
    deplacerEtCompterAbsences(ancienne, -2, 5);
    jdbc.update(
        "UPDATE evenements SET maintenu_le = now() WHERE id = CAST(:id AS uuid)",
        Map.of("id", ancienne.toString()));

    assertEquals(0, fusionProduitsEventim.fusionnerProduitsRepublies());
    assertTrue(existe(ancienne));
    assertTrue(existe(nouvelle));
  }

  @Test
  void fusionnerProduit_heureDifferenteDUneDemiHeure_fusionne() {
    final UUID a =
        ficheAvecObservations(
            salle,
            "dragonflies",
            "19:00",
            new String[] {"eventim", "https://www.eventim.fr/event/dragonflies-22234371", "1"});
    final UUID b =
        ficheAvecObservations(
            salle,
            "dragonflies",
            "18:30",
            new String[] {"eventim", "https://www.eventim.fr/event/dragonflies-22234371", "1"});

    assertEquals(1, fusionProduitsEventim.fusionner());
    assertEquals(1L, (existe(a) ? 1L : 0L) + (existe(b) ? 1L : 0L));
  }

  @Test
  void fusionnerProduit_heuresTropEloignees_neFusionnePas() {
    final UUID matinee =
        ficheAvecObservations(
            salle,
            "boleros",
            "15:30",
            new String[] {"eventim", "https://www.eventim.fr/event/boleros-22000001", "1"});
    final UUID soiree =
        ficheAvecObservations(
            salle,
            "boleros",
            "20:30",
            new String[] {"eventim", "https://www.eventim.fr/event/boleros-22000001", "1"});

    assertEquals(0, fusionProduitsEventim.fusionner());
    assertTrue(existe(matinee));
    assertTrue(existe(soiree));
  }

  @Test
  void fusionnerProduit_troisFichesPourLeProduit_neFusionnePas() {
    for (final String heure : List.of("20:00", "20:15", "20:30")) {
      ficheAvecObservations(
          salle,
          "titre " + heure,
          heure,
          new String[] {"eventim", "https://www.eventim.fr/event/titre-22000002", "1"});
    }

    assertEquals(0, fusionProduitsEventim.fusionner());
    assertEquals(3L, jdbc.queryForObject("SELECT count(*) FROM evenements", Map.of(), Long.class));
  }

  @Test
  void fusionnerProduit_deuxArtistesReconnusDifferents_neFusionnePas() {
    final UUID a =
        ficheAvecObservations(
            salle,
            "premier",
            "20:00",
            new String[] {"eventim", "https://www.eventim.fr/event/premier-22000003", "1"});
    final UUID b =
        ficheAvecObservations(
            salle,
            "second",
            "20:00",
            new String[] {"eventim", "https://www.eventim.fr/event/second-22000003", "1"});
    jdbc.update(
        "UPDATE evenements SET artiste_id = CAST(:artiste AS uuid) WHERE id = CAST(:id AS uuid)",
        Map.of("artiste", creerArtiste("Premier").toString(), "id", a.toString()));
    jdbc.update(
        "UPDATE evenements SET artiste_id = CAST(:artiste AS uuid) WHERE id = CAST(:id AS uuid)",
        Map.of("artiste", creerArtiste("Second").toString(), "id", b.toString()));

    assertEquals(0, fusionProduitsEventim.fusionner());
  }

  // Yael Naim : la fiche de 19 h 30 porte les deux sources, Ticketmaster republie à 20 h 30.
  @Test
  void fusionnerMemeSeanceDeuxSourcesHeuresProches_ficheDeuxSourcesEtFicheUneSource_fusionne() {
    final UUID portee =
        ficheAvecObservations(
            salle,
            "yael naim",
            "19:30",
            new String[] {"ticketmaster", "https://tm/idmanif/1", "20"},
            new String[] {"eventim", "https://www.eventim.fr/event/yael-naim-22000004", "1"});
    final UUID republiee =
        ficheAvecObservations(
            salle,
            "yael naim",
            "20:30",
            new String[] {"ticketmaster", "https://tm/idmanif/1/2", "1"});

    assertEquals(1, fusionEvenementsArtiste.fusionnerMemeSeanceDeuxSourcesHeuresProches());
    assertTrue(existe(portee));
    assertFalse(existe(republiee));
  }

  @Test
  void fusionnerMemeSeanceDeuxSourcesHeuresProches_deuxFichesDeuxSources_neFusionnePas() {
    ficheAvecObservations(
        salle,
        "deux sources",
        "19:30",
        new String[] {"ticketmaster", "https://tm/a", "1"},
        new String[] {"eventim", "https://www.eventim.fr/event/a-22000005", "1"});
    ficheAvecObservations(
        salle,
        "deux sources",
        "20:30",
        new String[] {"ticketmaster", "https://tm/b", "1"},
        new String[] {"eventim", "https://www.eventim.fr/event/b-22000006", "1"});

    assertEquals(0, fusionEvenementsArtiste.fusionnerMemeSeanceDeuxSourcesHeuresProches());
  }

  private void statut(final UUID id, final String statut, final String date) {
    jdbc.update(
        "UPDATE evenements SET statut = :statut, date = CAST(:date AS date)"
            + " WHERE id = CAST(:id AS uuid)",
        Map.of("statut", statut, "date", date, "id", id.toString()));
  }

  // Izia : Ticketmaster déplace la date d'un an, Eventim publie encore la date d'origine.
  @Test
  void marquerFichesDOrigineDesReports_dateReporteeParTmEtOrigineChezEventim_marqueIndisponible() {
    final UUID reportee =
        ficheAvecObservations(
            salle, "izia", "20:30", new String[] {"ticketmaster", "https://tm/idmanif/9", "1"});
    statut(reportee, "rescheduled", LocalDate.now().plusMonths(12).toString());
    final UUID origine =
        ficheAvecObservations(
            salle,
            "izia",
            "20:30",
            new String[] {"eventim", "https://www.eventim.fr/event/izia-22000007", "1"});

    assertEquals(1, fusionProduitsEventim.marquerFichesDOrigineDesReports());
    assertEquals(
        "cancelled",
        jdbc.queryForObject(
            "SELECT statut::text FROM evenements WHERE id = CAST(:id AS uuid)",
            Map.of("id", origine.toString()),
            String.class));
    assertEquals(
        "rescheduled",
        jdbc.queryForObject(
            "SELECT statut::text FROM evenements WHERE id = CAST(:id AS uuid)",
            Map.of("id", reportee.toString()),
            String.class));
  }

  @Test
  void marquerFichesDOrigineDesReports_deuxFichesEventimPossibles_neMarqueRien() {
    final UUID reportee =
        ficheAvecObservations(
            salle, "izia", "20:30", new String[] {"ticketmaster", "https://tm/idmanif/9", "1"});
    statut(reportee, "rescheduled", LocalDate.now().plusMonths(12).toString());
    ficheAvecObservations(
        salle,
        "izia",
        "20:30",
        new String[] {"eventim", "https://www.eventim.fr/event/izia-22000007", "1"});
    final UUID autreDate =
        ficheAvecObservations(
            salle,
            "izia",
            "20:45",
            new String[] {"eventim", "https://www.eventim.fr/event/izia-22000008", "1"});
    jdbc.update(
        "UPDATE evenements SET date = date + 7 WHERE id = CAST(:id AS uuid)",
        Map.of("id", autreDate.toString()));

    assertEquals(0, fusionProduitsEventim.marquerFichesDOrigineDesReports());
  }

  @Test
  void marquerFichesDOrigineDesReports_heureEloignee_neMarquePas() {
    final UUID reportee =
        ficheAvecObservations(
            salle, "izia", "20:30", new String[] {"ticketmaster", "https://tm/idmanif/9", "1"});
    statut(reportee, "rescheduled", LocalDate.now().plusMonths(12).toString());
    ficheAvecObservations(
        salle,
        "izia",
        "15:00",
        new String[] {"eventim", "https://www.eventim.fr/event/izia-22000009", "1"});

    assertEquals(0, fusionProduitsEventim.marquerFichesDOrigineDesReports());
  }
}
