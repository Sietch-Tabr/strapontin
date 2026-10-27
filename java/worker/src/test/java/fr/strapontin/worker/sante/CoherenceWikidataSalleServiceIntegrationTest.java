/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.sources.WikidataSalleSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Sélection des salles du contrôle de cohérence Wikidata par commune
 * ({@link CoherenceWikidataSalleService}) sur base réelle.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class CoherenceWikidataSalleServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;
  @MockitoBean WikidataSalleSource wikidataSalleSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private CoherenceWikidataSalleService coherence;

  @BeforeEach
  void nettoyer() {
    jdbc.update("DELETE FROM salles", Map.of());
    when(wikidataSalleSource.enrichirLot(any())).thenReturn(Map.of());
  }

  private UUID salleWikidata(final String nom, final boolean lieuPositionne) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, ville, dept, wikidata_id, latitude,"
            + " longitude, origine_position, coherence_wikidata_signalee) VALUES"
            + " (CAST(:id AS uuid), :nom, :nom, :nom, 'Bordeaux', '33', :qid, 44.83, -0.64,"
            + " 'wikidata', 'Mérignac|false')",
        Map.of("id", id.toString(), "nom", nom, "qid", "Q" + Math.abs(nom.hashCode())));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, latitude, longitude)"
            + " VALUES (CAST(:id AS uuid), 'eventim', :ident, :lat, :lon)",
        new MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("ident", "internal:" + nom)
            .addValue("lat", lieuPositionne ? 44.831 : null, Types.DOUBLE)
            .addValue("lon", lieuPositionne ? -0.641 : null, Types.DOUBLE));
    return id;
  }

  // Ldlc Arena : élément Wikidata avec coordonnées, aucune position en base.
  @Test
  void poserPositionsManquantes_salleAvecElementSansPosition_recoitCelleDeWikidata() {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, ville, dept, wikidata_id) VALUES"
            + " (CAST(:id AS uuid), 'ldlc arena', 'Ldlc Arena', 'ldlc arena', 'Décines', '69',"
            + " 'Q107719069')",
        Map.of("id", id.toString()));
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q107719069", "Ldlc Arena", "Décines", null, null, 45.7, 4.98)));

    assertEquals(1, coherence.poserPositionsManquantes());

    final Map<String, Object> salle =
        jdbc.queryForMap(
            "SELECT latitude, longitude, origine_position FROM salles"
                + " WHERE id = CAST(:id AS uuid)",
            Map.of("id", id.toString()));
    assertEquals(45.7, salle.get("latitude"));
    assertEquals("wikidata", salle.get("origine_position"));
  }

  // Le Transbordeur : stocké à Lyon, Wikidata et les concerts disent Villeurbanne.
  @Test
  void verifier_communeWikidataConfirmeeParLesConcerts_alignelaVilleDeLaSalle() {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, ville, dept, wikidata_id) VALUES"
            + " (CAST(:id AS uuid), 'le transbordeur', 'Le Transbordeur', 'le transbordeur',"
            + " 'Lyon', '69', 'Q3227778')",
        Map.of("id", id.toString()));
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, ville, dept)"
            + " VALUES ('a', CURRENT_DATE + 10, '20:00', 'x', CAST(:id AS uuid), 'Villeurbanne',"
            + " '69')",
        Map.of("id", id.toString()));
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " SELECT id, 'eventim', 'u', 'a', 's' FROM evenements",
        Map.of());
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q3227778", "Le Transbordeur", "Villeurbanne", null, null)));

    coherence.verifier();

    assertEquals(
        "Villeurbanne",
        jdbc.queryForObject(
            "SELECT ville FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", id.toString()),
            String.class));
  }

  // Alhambra : élément détruit retiré, et le journal nomme la salle.
  @Test
  void revaliderTypes_elementInvalide_journalNommeLaSalle() {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, ville, dept, wikidata_id) VALUES"
            + " (CAST(:id AS uuid), 'alhambra', 'Alhambra', 'alhambra', 'Paris', '75',"
            + " 'Q4724424')",
        Map.of("id", id.toString()));
    jdbc.update("DELETE FROM logs_admin", Map.of());
    when(wikidataSalleSource.typesInvalides(any())).thenReturn(Set.of("Q4724424"));

    assertEquals(1, coherence.revaliderTypes());

    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM logs_admin WHERE message LIKE '%salle(s)=Alhambra%'",
            Map.of(),
            Long.class));
    assertNull(
        jdbc.queryForObject(
            "SELECT wikidata_id FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", id.toString()),
            String.class));
  }

  // Le Pin Galant (Mérignac) : position Wikidata confirmée par son lieu, le contrôle de position
  // tranche ; la commune « Mérignac » face à des concerts annoncés à Bordeaux était un faux
  // positif.
  @Test
  @SuppressWarnings("unchecked")
  void verifier_positionWikidataConfirmeeParUnLieu_nEstPlusControleeParCommune() {
    final UUID pinGalant = salleWikidata("le pin galant", true);
    final UUID sansLieu = salleWikidata("salle sans lieu", false);

    coherence.verifier();

    final ArgumentCaptor<List<SalleAEnrichir>> captor = ArgumentCaptor.forClass(List.class);
    verify(wikidataSalleSource).enrichirLot(captor.capture());
    assertEquals(List.of(sansLieu), captor.getValue().stream().map(SalleAEnrichir::id).toList());
    assertEquals(
        null,
        jdbc.queryForObject(
            "SELECT coherence_wikidata_signalee FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", pinGalant.toString()),
            String.class));
  }

  // Le Pin Galant positionné par ses lieux publiés : l'élément Wikidata (Mérignac) est proche
  // de la salle, des concerts annoncés à « Bordeaux » n'en font pas un homonyme.
  @Test
  void verifier_positionDesLieuxProcheDeLElement_nEstPasSignalee() {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, ville, dept, wikidata_id, latitude,"
            + " longitude, origine_position) VALUES (CAST(:id AS uuid), 'le pin galant', 'Le Pin"
            + " Galant', 'le pin galant', 'Mérignac', '33', 'Q61854714', 44.8409, -0.6481,"
            + " 'sources')",
        Map.of("id", id.toString()));
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q61854714", "Le Pin Galant", "Bordeaux", null, null, 44.8404, -0.6475)));

    assertEquals(0, coherence.verifier());
    assertNull(
        jdbc.queryForObject(
            "SELECT coherence_wikidata_signalee FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", id.toString()),
            String.class));
  }

  // Salle Paul Fort (Nantes) rattachée à la salle Paul Bouin de Basse-Goulaine, à 7 km.
  @Test
  void verifier_elementDUnAutreLieuAQuelquesKilometres_estSignale() {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, ville, dept, wikidata_id, latitude,"
            + " longitude, origine_position) VALUES (CAST(:id AS uuid), 'salle paul fort', 'Salle"
            + " Paul Fort', 'salle paul fort', 'Basse-Goulaine', '44', 'Q105714379', 47.22080,"
            + " -1.55840, 'sources')",
        Map.of("id", id.toString()));
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q105714379",
                    "salle Paul Bouin",
                    "Basse-Goulaine",
                    null,
                    null,
                    47.21865,
                    -1.46460)));

    assertEquals(1, coherence.verifier());
  }

  // Espace Des Arts (Les Pavillons-sous-Bois, Q61860839) positionné à Chalon-sur-Saône.
  @Test
  void realignerPositions_positionLoinDeSonElement_estRealigneeEtTracee() {
    final UUID salle = salleWikidata("espace des arts", true);
    jdbc.update(
        "UPDATE salles SET latitude = 46.78, longitude = 4.85 WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(
                salle,
                new EnrichissementSalle("Q61860839", null, null, null, null, 48.903, 2.507)));

    assertEquals(1, coherence.realignerPositions());

    final Map<String, Object> position =
        jdbc.queryForMap(
            "SELECT latitude, longitude, origine_position FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()));
    assertEquals(48.903, (Double) position.get("latitude"), 1e-9);
    assertEquals(2.507, (Double) position.get("longitude"), 1e-9);
    assertEquals("wikidata", position.get("origine_position"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM logs_admin WHERE message LIKE 'position de salle réalignée%'",
            Map.of(), Integer.class));
  }

  @Test
  void realignerPositions_positionDejaCelleDeLElement_inchangee() {
    final UUID salle = salleWikidata("arena", true);
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(salle, new EnrichissementSalle("Q1", null, null, null, null, 44.8301, -0.6401)));

    assertEquals(0, coherence.realignerPositions());
  }

  private UUID salleParUneSource(final String nom, final String origine) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, ville, dept, wikidata_id, latitude,"
            + " longitude, origine_position) VALUES (CAST(:id AS uuid), :nom, :nom, :nom,"
            + " 'Montpellier', '34', :qid, 43.6108, 3.8767, :origine)",
        Map.of(
            "id",
            id.toString(),
            "nom",
            nom,
            "qid",
            "Q" + Math.abs(nom.hashCode()),
            "origine",
            origine));
    return id;
  }

  private int signalementsPosition() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM signalements_matching"
            + " WHERE type = 'position_source_eloignee' AND resolu_le IS NULL",
        Map.of(),
        Integer.class);
  }

  // Sud de France Arena : position TM au centre de Montpellier, élément Wikidata à Pérols.
  @Test
  void signalerPositionsDivergentes_positionDeSourceLoinDeLElement_estSignaleeUneSeuleFois() {
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    final UUID salle = salleParUneSource("sud de france arena", "sources");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, latitude, longitude)"
            + " VALUES (CAST(:id AS uuid), 'ticketmaster', 'rZ7SnyZadrK', 43.6108, 3.8767)",
        Map.of("id", salle.toString()));
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(salle, new EnrichissementSalle("Q1", null, null, null, null, 43.5686, 3.9519)));

    assertEquals(1, coherence.signalerPositionsDivergentes());
    assertEquals(1, coherence.signalerPositionsDivergentes());

    assertEquals(1, signalementsPosition());
    final String details =
        jdbc.queryForObject(
            "SELECT details::text FROM signalements_matching"
                + " WHERE type = 'position_source_eloignee'",
            Map.of(),
            String.class);
    assertTrue(details.contains("\"wikidataId\": \"Q"), details);
    assertTrue(details.contains("ticketmaster rZ7SnyZadrK : 7"), details);
    assertTrue(details.contains("\"latitudeWikidata\": 43.5686"), details);
    assertTrue(details.contains("m de l'élément"), details);
    assertEquals(
        43.6108,
        jdbc.queryForObject(
            "SELECT latitude FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()),
            Double.class),
        1e-9);
  }

  // Zénith Paris - La Villette : position de billetterie à 947 m de l'élément Wikidata.
  @Test
  void signalerPositionsDivergentes_ecartDeQuelquesCentainesDeMetres_alignesurWikidata() {
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    final UUID salle = salleParUneSource("zenith la villette", "sources");
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(salle, new EnrichissementSalle("Q3", null, null, null, null, 43.6108, 3.8860)));

    assertEquals(0, coherence.signalerPositionsDivergentes());

    final Map<String, Object> position =
        jdbc.queryForMap(
            "SELECT longitude, origine_position FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()));
    assertEquals(3.8860, (Double) position.get("longitude"), 1e-9);
    assertEquals("wikidata", position.get("origine_position"));
    assertEquals(0, signalementsPosition());
  }

  @Test
  void signalerPositionsDivergentes_positionProcheOuPosee_parWikidata_neSignaleRien() {
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    final UUID proche = salleParUneSource("salle proche", "sources");
    salleParUneSource("salle wikidata", "wikidata");
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(proche, new EnrichissementSalle("Q2", null, null, null, null, 43.6112, 3.8770)));

    assertEquals(0, coherence.signalerPositionsDivergentes());
    assertEquals(0, signalementsPosition());
  }

  @Test
  void signalerPositionsDivergentes_ecartDisparu_refermeLeSignalement() {
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    final UUID salle = salleParUneSource("sud de france arena", "sources");
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(salle, new EnrichissementSalle("Q1", null, null, null, null, 43.5686, 3.9519)));
    coherence.signalerPositionsDivergentes();
    assertEquals(1, signalementsPosition());

    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(salle, new EnrichissementSalle("Q1", null, null, null, null, 43.6112, 3.8770)));
    coherence.signalerPositionsDivergentes();

    assertEquals(0, signalementsPosition());
  }

  @Test
  void signalerPositionsDivergentes_elementIllisible_gardeLeSignalement() {
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    final UUID salle = salleParUneSource("sud de france arena", "sources");
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(salle, new EnrichissementSalle("Q1", null, null, null, null, 43.5686, 3.9519)));
    coherence.signalerPositionsDivergentes();

    when(wikidataSalleSource.enrichirLot(any())).thenReturn(Map.of());
    coherence.signalerPositionsDivergentes();

    assertEquals(1, signalementsPosition());
  }
}
