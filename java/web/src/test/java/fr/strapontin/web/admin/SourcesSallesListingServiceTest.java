/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link SourcesSallesListingService} (pagination des salles pour l'onglet
 * admin « Sources », dépendance mockée).
 *
 * @since 2.9.1
 * @author Kwaite
 */
class SourcesSallesListingServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private SourcesSallesListingService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    service = new SourcesSallesListingService(jdbc);
  }

  @Test
  void lister_vueSansSource_excluTMEtEventim() {
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(jdbc.query(
            sqlCaptor.capture(),
            anyMap(),
            org.mockito.ArgumentMatchers.<RowMapper<SalleSourceAdmin>>any()))
        .thenReturn(List.of());

    service.lister(false, 0, null);

    final String sqlSalles =
        sqlCaptor.getAllValues().stream()
            .filter(sql -> sql.contains("HAVING"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Aucune requête avec HAVING trouvée"));
    assertTrue(
        sqlSalles.contains("'ticketmaster'"),
        "La requête doit filtrer les salles sans source ticketmaster");
    assertTrue(
        sqlSalles.contains("'eventim'"), "La requête doit filtrer les salles sans source eventim");
  }

  @Test
  void lister_lesDeuxVues_utilisentColonneId_pasSalleUuid() {
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(jdbc.query(
            sqlCaptor.capture(),
            anyMap(),
            org.mockito.ArgumentMatchers.<RowMapper<SalleSourceAdmin>>any()))
        .thenReturn(List.of());

    service.lister(false, 0, null);
    service.lister(true, 0, null);

    sqlCaptor
        .getAllValues()
        .forEach(
            sql ->
                assertFalse(
                    sql.contains("salle_uuid"),
                    "Requête SQL référence encore salle_uuid (colonne supprimée en V24) : " + sql));
  }

  @Test
  void lister_nominal_retourneLesSalles() {
    final UUID uuid = UUID.randomUUID();
    when(jdbc.query(
            anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<SalleSourceAdmin>>any()))
        .thenReturn(List.of(new SalleSourceAdmin(uuid, "L'Olympia", null, null, null)));

    final ReponseSallesSources reponse = service.lister(false, 0, null);

    assertEquals(1, reponse.salles().size());
    assertEquals("L'Olympia", reponse.salles().get(0).nom());
    assertNull(reponse.salles().get(0).lieuEventim());
  }

  // Seuls les lieux Eventim réellement interrogeables (libellé connu) sont listés.
  @Test
  void lister_vueLieuEventim_filtreSurLibelleConnu() {
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(jdbc.query(
            sqlCaptor.capture(),
            anyMap(),
            org.mockito.ArgumentMatchers.<RowMapper<SalleSourceAdmin>>any()))
        .thenReturn(List.of());

    service.lister(true, 0, null);

    assertTrue(sqlCaptor.getValue().contains("ss.source = 'eventim'"));
    assertTrue(sqlCaptor.getValue().contains("libelle_source IS NOT NULL"));
  }
}
