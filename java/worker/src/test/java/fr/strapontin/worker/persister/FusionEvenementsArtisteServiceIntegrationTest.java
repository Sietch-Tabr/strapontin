/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.sources.TicketmasterSource;
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
    assertEquals(
        3L, jdbc.queryForObject("SELECT COUNT(*) FROM evenements", Map.of(), Long.class));
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
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id) VALUES"
                + " (:titre, CAST(:date AS date), CAST('19:30' AS time), 'opera bastille',"
                + " CAST(:salleId AS uuid)) RETURNING id",
            Map.of("titre", titre, "date", DATE_FUTURE, "salleId", salleId.toString()),
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

  // Lady Macbeth de Mzensk : « of Mtsensk » chez Ticketmaster, « de Mzensk » chez Eventim.
  @Test
  void fusionnerMemeCreneauDeuxSources_memeSpectacleDeuxIntitules_fusionne() {
    final UUID bastille = creerSalle("Opera Bastille");
    final UUID tm = evenementSource(bastille, "lady macbeth of mtsensk", "ticketmaster");
    final UUID ev = evenementSource(bastille, "lady macbeth de mzensk", "eventim");

    assertEquals(1, fusionEvenementsArtiste.fusionnerMemeCreneauDeuxSources());
    assertTrue(existe(tm));
    assertFalse(existe(ev));
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

    assertEquals(0, fusionEvenementsArtiste.fusionnerMemeCreneauDeuxSources());
  }

  @Test
  void fusionnerMemeCreneauDeuxSources_troisEvenementsAuCreneau_neFusionnePas() {
    final UUID salle = creerSalle("Salle Gaveau");
    evenementSource(salle, "antonio vivaldi", "ticketmaster");
    evenementSource(salle, "bebe concert", "eventim");
    evenementSource(salle, "gloria de vivaldi", "eventim");

    assertEquals(0, fusionEvenementsArtiste.fusionnerMemeCreneauDeuxSources());
  }
}
