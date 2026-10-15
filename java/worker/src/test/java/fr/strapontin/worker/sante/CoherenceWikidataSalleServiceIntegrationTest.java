/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        "SELECT count(*) FROM logs_admin WHERE niveau = 'warn'"
            + " AND message LIKE 'position de source éloignée%'",
        Map.of(), Integer.class);
  }

  // Sud de France Arena : position TM au centre de Montpellier, élément Wikidata à Pérols.
  @Test
  void signalerPositionsDivergentes_positionDeSourceLoinDeLElement_estSignaleeUneFoisParMois() {
    jdbc.update("DELETE FROM logs_admin", Map.of());
    final UUID salle = salleParUneSource("sud de france arena", "sources");
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(salle, new EnrichissementSalle("Q1", null, null, null, null, 43.5686, 3.9519)));

    assertEquals(1, coherence.signalerPositionsDivergentes());
    assertEquals(1, coherence.signalerPositionsDivergentes());

    assertEquals(1, signalementsPosition());
    assertEquals(
        43.6108,
        jdbc.queryForObject(
            "SELECT latitude FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()),
            Double.class),
        1e-9);
  }

  @Test
  void signalerPositionsDivergentes_positionProcheOuPosee_parWikidata_neSignaleRien() {
    jdbc.update("DELETE FROM logs_admin", Map.of());
    final UUID proche = salleParUneSource("salle proche", "sources");
    salleParUneSource("salle wikidata", "wikidata");
    when(wikidataSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(proche, new EnrichissementSalle("Q2", null, null, null, null, 43.6112, 3.8770)));

    assertEquals(0, coherence.signalerPositionsDivergentes());
    assertEquals(0, signalementsPosition());
  }
}
