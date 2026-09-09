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

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link ReferentielSallesService}.
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
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper<SalleReferentiel> mapper = invocation.getArgument(2);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
              when(rs.getString("nom")).thenReturn("Zenith");
              when(rs.getString("nom_affiche")).thenReturn("Zénith de Paris");
              when(rs.getString("ville")).thenReturn("Paris");
              when(rs.getString("dept")).thenReturn("75");
              when(rs.getLong("nb_concerts")).thenReturn(5L);
              when(rs.getString("sources")).thenReturn("ticketmaster");
              when(rs.getString("statut")).thenReturn("a_jour");
              when(rs.getString("wikidata_id")).thenReturn("Q123");
              when(rs.getLong("total_filtre")).thenReturn(3L);
              return List.of(mapper.mapRow(rs, 0));
            });

    ReponseReferentielSalles reponse = service.lister(0, null, null, null, null, false);

    assertEquals(1, reponse.salles().size());
    assertEquals(3L, reponse.totalSalles());
    assertEquals(0, reponse.page());
  }

  // Régression : les valeurs de statut exposées doivent être autoporteuses
  // (a_jour/a_verifier/invalide) plutôt que vivant/suspect/mort, peu clairs hors contexte.
  @Test
  @SuppressWarnings("unchecked")
  void lister_nominal_requeteUtiliseLesNouvellesValeursDeStatut() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    service.lister(0, null, null, null, null, false);

    verify(jdbc)
        .query(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) ->
                    sql.contains("'a_jour'")
                        && sql.contains("'a_verifier'")
                        && sql.contains("'invalide'")),
            anyMap(),
            any(RowMapper.class));
  }

  // Régression : facilite le repérage des candidats à corriger manuellement dans une pagination
  // de 300+ salles, sans devoir parcourir page par page (cf. retour utilisateur).
  @Test
  @SuppressWarnings("unchecked")
  void lister_wikidataManquantVrai_filtreSurWikidataIdNull() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    service.lister(0, null, null, null, null, true);

    verify(jdbc).query(contains("wikidata_id IS NULL"), anyMap(), any(RowMapper.class));
  }

  // page au-delà du total (offset trop grand) : aucune ligne ne porte total_filtre, un second
  // aller-retour COUNT reste nécessaire pour ne pas afficher un total à zéro trompeur.
  @Test
  @SuppressWarnings("unchecked")
  void lister_pageAuDelaDuTotal_retombeSurUnCountSepare() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(3L);

    ReponseReferentielSalles reponse = service.lister(5, null, null, null, null, false);

    assertEquals(3L, reponse.totalSalles());
    assertTrue(reponse.salles().isEmpty());
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
    verify(jdbc, times(1)).update(contains("wikidata_id = COALESCE"), anyMap());
    // Le cas particulier (cible sous-salle de la source) s'exécute toujours ; aucune autre
    // sous-salle à réassigner ici (query non stubbée retourne une liste vide par défaut).
    verify(jdbc, times(1)).update(contains("UPDATE salles SET salle_parent_id"), anyMap());
    verify(jdbc, times(1)).update(contains("DELETE FROM salles WHERE"), anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), any());
  }

  // #1201 : réassigner en bloc les sous-salles de la source vers la cible peut créer un cycle
  // quand la cible est déjà une descendante (via un intermédiaire) de la source.
  @Test
  @SuppressWarnings("unchecked")
  void fusionner_sousSalleDescendanteDeLaCible_detacheSansCreerDeCycle() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(2L);
    UUID cible = UUID.randomUUID();
    UUID source = UUID.randomUUID();
    UUID intermediaire = UUID.randomUUID();

    when(jdbc.query(
            contains("SELECT id FROM salles WHERE salle_parent_id"),
            anyMap(),
            any(RowMapper.class)))
        .thenReturn(List.of(intermediaire));
    // cible -> intermediaire déjà en base : réassigner intermediaire -> cible créerait un cycle.
    when(jdbc.query(contains("SELECT salle_parent_id FROM salles"), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of(intermediaire));

    boolean fusionnee = service.fusionner(cible, source);

    assertTrue(fusionnee);
    verify(jdbc)
        .update(
            contains("UPDATE salles SET salle_parent_id = NULL"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params -> intermediaire.toString().equals(params.get("id"))));
    verify(logAdminService).logAdmin(eq("warn"), eq("admin"), any());
  }

  // deux identifiants du même type de source (ex. deux ticketmaster) sur cible et source
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
}
