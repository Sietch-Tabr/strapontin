/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link ReferentielArtistesService} (#710).
///
/// @since 2.6.0
/// @author Kwaite
class ReferentielArtistesServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogAdminService logAdminService;
  private ReferentielArtistesService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logAdminService = mock(LogAdminService.class);
    service = new ReferentielArtistesService(jdbc, logAdminService);
  }

  @Test
  @SuppressWarnings("unchecked")
  void lister_nominal_retournePageAvecTotal() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenReturn(
            List.of(
                new ArtisteReferentiel(
                    UUID.randomUUID(),
                    "Jain",
                    "Jain",
                    2,
                    List.of("ticketmaster"),
                    "a_jour",
                    "Q3161164",
                    null)));

    ReponseReferentielArtistes reponse = service.lister(0, "Jain", null, false);

    assertEquals(1, reponse.artistes().size());
    assertEquals(1L, reponse.totalArtistes());
  }

  // Régression #778 : les valeurs de statut exposées doivent être autoporteuses
  // (a_jour/a_verifier/invalide) plutôt que vivant/suspect/mort, peu clairs hors contexte.
  @Test
  @SuppressWarnings("unchecked")
  void lister_nominal_requeteUtiliseLesNouvellesValeursDeStatut() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    service.lister(0, null, null, false);

    verify(jdbc)
        .queryForObject(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) ->
                    sql.contains("'a_jour'")
                        && sql.contains("'a_verifier'")
                        && sql.contains("'invalide'")),
            anyMap(),
            eq(Long.class));
  }

  // Régression : facilite le repérage des candidats à corriger manuellement dans une pagination
  // volumineuse, sans devoir parcourir page par page.
  @Test
  @SuppressWarnings("unchecked")
  void lister_wikidataManquantVrai_filtreSurWikidataIdNull() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    service.lister(0, null, null, true);

    verify(jdbc).queryForObject(contains("wikidata_id IS NULL"), anyMap(), eq(Long.class));
    verify(jdbc).query(contains("wikidata_id IS NULL"), anyMap(), any(RowMapper.class));
  }

  // Régression : corrige une mauvaise correspondance automatique — l'enrichissement ne peut
  // jamais s'auto-corriger via COALESCE, il faut explicitement effacer pour redevenir candidat.
  @Test
  void reinitialiserEnrichissement_artisteExistant_effaceLesTroisChamps() {
    UUID id = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    boolean maj = service.reinitialiserEnrichissement(id);

    assertTrue(maj);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(
                sql ->
                    sql.contains("wikidata_id = NULL")
                        && sql.contains("deezer_id = NULL")
                        && sql.contains("nom_affiche = NULL")),
            anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), anyString(), anyMap());
  }

  @Test
  void reinitialiserEnrichissement_artisteInconnu_retourneFalse() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    assertFalse(service.reinitialiserEnrichissement(UUID.randomUUID()));
  }

  @Test
  void modifierNomAffiche_artisteInconnu_retourneFalse() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    assertFalse(service.modifierNomAffiche(UUID.randomUUID(), "Nouveau nom"));
  }

  @Test
  void fusionner_uuidIdentiques_retourneFalseSansAppelBase() {
    UUID id = UUID.randomUUID();

    assertFalse(service.fusionner(id, id));
    verify(jdbc, never()).queryForObject(anyString(), anyMap(), eq(Long.class));
  }

  @Test
  void fusionner_unArtisteInconnu_retourneFalse() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L);

    assertFalse(service.fusionner(UUID.randomUUID(), UUID.randomUUID()));
    verify(jdbc, never()).update(contains("DELETE FROM artistes"), anyMap());
  }

  @Test
  void fusionner_nominal_reassigneEtSupprimeLaSource() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(2L);
    UUID cible = UUID.randomUUID();
    UUID source = UUID.randomUUID();

    boolean fusionne = service.fusionner(cible, source);

    assertTrue(fusionne);
    verify(jdbc, times(1)).update(contains("DELETE FROM concerts c1"), anyMap());
    verify(jdbc, times(1)).update(contains("UPDATE concerts SET artiste_id"), anyMap());
    verify(jdbc, times(1)).update(contains("DELETE FROM user_artistes"), anyMap());
    verify(jdbc, times(1)).update(contains("UPDATE user_artistes SET artiste_id"), anyMap());
    verify(jdbc, times(1)).update(contains("UPDATE artiste_sources SET artiste_id"), anyMap());
    verify(jdbc, times(1)).update(contains("UPDATE artistes SET"), anyMap());
    verify(jdbc, times(1)).update(contains("DELETE FROM artistes WHERE"), anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), any());
  }

  // #828 : deux artistes fusionnés peuvent chacun avoir enregistré le même concert réel (même
  // salle, même date/heure) sous un artiste_norm textuel différent selon la source — sans ce
  // nettoyage préalable, les deux lignes concerts subsistaient après fusion, dupliquant le
  // concert sur la fiche de l'artiste cible. Aligné sur ReferentielSallesService.fusionner.
  @Test
  void fusionner_concertEnConflitCoteCible_dedoublonneAvantRemapping() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(2L);
    UUID cible = UUID.randomUUID();
    UUID source = UUID.randomUUID();

    service.fusionner(cible, source);

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) ->
                    sql.contains("DELETE FROM concerts c1")
                        && sql.contains("USING concerts c2")
                        && sql.contains("c1.artiste_id = CAST(:source AS uuid)")
                        && sql.contains("c2.artiste_id = CAST(:cible AS uuid)")
                        && sql.contains("c1.salle_uuid = c2.salle_uuid")
                        && sql.contains("c1.date = c2.date")),
            anyMap());
  }
}
