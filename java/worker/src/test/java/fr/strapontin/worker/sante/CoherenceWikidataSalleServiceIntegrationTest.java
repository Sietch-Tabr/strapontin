/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
