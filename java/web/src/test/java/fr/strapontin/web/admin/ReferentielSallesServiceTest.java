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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link ReferentielSallesService} (#710).
///
/// @since 2.6.0
/// @author Kwaite
class ReferentielSallesServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogAdminService logAdminService;
  private ReferentielSallesService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logAdminService = mock(LogAdminService.class);
    service = new ReferentielSallesService(jdbc, logAdminService);
  }

  @Test
  @SuppressWarnings("unchecked")
  void lister_nominal_retournePageAvecTotal() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(3L);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenReturn(
            List.of(
                new SalleReferentiel(
                    UUID.randomUUID(),
                    "Zenith",
                    "Zénith de Paris",
                    "Paris",
                    "75",
                    5,
                    List.of("ticketmaster"),
                    "a_jour",
                    "Q123")));

    ReponseReferentielSalles reponse = service.lister(0, null, null, null, null, false);

    assertEquals(1, reponse.salles().size());
    assertEquals(3L, reponse.totalSalles());
    assertEquals(0, reponse.page());
  }

  // Régression #778 : les valeurs de statut exposées doivent être autoporteuses
  // (a_jour/a_verifier/invalide) plutôt que vivant/suspect/mort, peu clairs hors contexte.
  @Test
  @SuppressWarnings("unchecked")
  void lister_nominal_requeteUtiliseLesNouvellesValeursDeStatut() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    service.lister(0, null, null, null, null, false);

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
  // de 300+ salles, sans devoir parcourir page par page (cf. retour utilisateur).
  @Test
  @SuppressWarnings("unchecked")
  void lister_wikidataManquantVrai_filtreSurWikidataIdNull() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    service.lister(0, null, null, null, null, true);

    verify(jdbc).queryForObject(contains("wikidata_id IS NULL"), anyMap(), eq(Long.class));
    verify(jdbc).query(contains("wikidata_id IS NULL"), anyMap(), any(RowMapper.class));
  }

  @Test
  void modifierNomAffiche_valeurBlanche_metNullEnBase() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    boolean maj = service.modifierNomAffiche(UUID.randomUUID(), "  ");

    assertTrue(maj);
    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> p.get("nomAffiche") == null));
  }

  // Régression : corrige une mauvaise correspondance automatique (ex. homonyme international,
  // "Orléans Arena" ↔ Q3356467 "Orleans Arena" Las Vegas) — l'enrichissement ne peut jamais
  // s'auto-corriger via COALESCE, il faut explicitement effacer pour redevenir candidat.
  @Test
  void reinitialiserEnrichissement_salleExistante_effaceLesTroisChamps() {
    UUID id = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    boolean maj = service.reinitialiserEnrichissement(id);

    assertTrue(maj);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(
                sql ->
                    sql.contains("wikidata_id = NULL")
                        && sql.contains("nom_affiche = NULL")
                        && sql.contains("ville = NULL")),
            anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), anyString(), anyMap());
  }

  @Test
  void reinitialiserEnrichissement_salleInconnue_retourneFalse() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    assertFalse(service.reinitialiserEnrichissement(UUID.randomUUID()));
  }

  @Test
  void modifierNomAffiche_salleInconnue_retourneFalse() {
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
  void fusionner_uneSalleInconnue_retourneFalse() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L);

    assertFalse(service.fusionner(UUID.randomUUID(), UUID.randomUUID()));
    verify(jdbc, never()).update(contains("DELETE FROM salles"), anyMap());
  }

  @Test
  void fusionner_nominal_dedoublonneReassigneEtSupprimeLaSource() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(2L);
    UUID cible = UUID.randomUUID();
    UUID source = UUID.randomUUID();

    boolean fusionnee = service.fusionner(cible, source);

    assertTrue(fusionnee);
    verify(jdbc, times(1)).update(contains("DELETE FROM concerts c1"), anyMap());
    verify(jdbc, times(1)).update(contains("UPDATE concerts SET salle_id"), anyMap());
    verify(jdbc, times(1)).update(contains("DELETE FROM user_salles"), anyMap());
    verify(jdbc, times(1)).update(contains("UPDATE user_salles SET salle_id"), anyMap());
    verify(jdbc, times(1)).update(contains("UPDATE salle_sources SET salle_id"), anyMap());
    verify(jdbc, times(1)).update(contains("UPDATE salles SET"), anyMap());
    verify(jdbc, times(1)).update(contains("DELETE FROM salles WHERE"), anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), any());
  }

  // #1019 : deux identifiants du même type de source (ex. deux ticketmaster) sur cible et source
  // ne doivent pas coexister après fusion — la ligne la moins saine (suspecte) doit être
  // supprimée avant la réassignation en masse.
  @Test
  void fusionner_conflitSourceCommune_supprimeLaLigneSuspecte() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(2L);
    UUID cible = UUID.randomUUID();
    UUID source = UUID.randomUUID();

    Map<String, Object> conflit =
        Map.of(
            "source",
            "ticketmaster",
            "id_source",
            "tm-source-mort",
            "id_cible",
            "tm-cible-sain",
            "sante_source",
            5,
            "sante_cible",
            0,
            "suspect_source",
            true,
            "suspect_cible",
            false);
    when(jdbc.queryForList(contains("FROM salle_sources src"), anyMap()))
        .thenReturn(List.of(conflit));

    boolean fusionnee = service.fusionner(cible, source);

    assertTrue(fusionnee);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) -> sql.contains("DELETE FROM salle_sources WHERE salle_id")),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p ->
                    p.get("salleId").equals(source.toString())
                        && p.get("identifiantExterne").equals("tm-source-mort")));
  }

  @Test
  void laSourceEstPlusSaine_statutsIdentiquesEgaliteSante_gardeLaSource() {
    Map<String, Object> conflit =
        Map.of(
            "sante_source", 2, "sante_cible", 2, "suspect_source", false, "suspect_cible", false);

    assertTrue(ReferentielSallesService.laSourceEstPlusSaine(conflit));
  }
}
