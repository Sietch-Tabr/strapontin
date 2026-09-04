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

/// Tests unitaires pour {@link ReferentielArtistesService}.
///
/// @since 2.6.0
/// @author Kwaite
class ReferentielArtistesServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogAdminService logAdminService;
  private ReferentielArtistesService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logAdminService = mock(LogAdminService.class);
    service = new ReferentielArtistesService(jdbc, logAdminService);
  }

  @Test
  @SuppressWarnings("unchecked")
  void lister_nominal_retournePageAvecTotal() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper<ArtisteReferentiel> mapper = invocation.getArgument(2);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
              when(rs.getString("nom")).thenReturn("Jain");
              when(rs.getString("nom_affiche")).thenReturn("Jain");
              when(rs.getLong("nb_concerts")).thenReturn(2L);
              when(rs.getString("sources")).thenReturn("ticketmaster");
              when(rs.getString("statut")).thenReturn("a_jour");
              when(rs.getString("wikidata_id")).thenReturn("Q3161164");
              when(rs.getString("deezer_id")).thenReturn(null);
              when(rs.getString("tm_identifiant_externe")).thenReturn("K8vZ917G1B7");
              when(rs.getLong("total_filtre")).thenReturn(1L);
              return List.of(mapper.mapRow(rs, 0));
            });

    ReponseReferentielArtistes reponse = service.lister(0, "Jain", null, false);

    assertEquals(1, reponse.artistes().size());
    assertEquals(1L, reponse.totalArtistes());
  }

  // Régression : les valeurs de statut exposées doivent être autoporteuses
  // (a_jour/invalide) plutôt que vivant/suspect/mort, peu clairs hors contexte.
  // statut désormais binaire (necessite_intervention_le), 'a_verifier' n'est plus jamais
  // retourné pour un artiste — le compteur passif seul générait trop de faux positifs (65%).
  @Test
  @SuppressWarnings("unchecked")
  void lister_nominal_requeteUtiliseLesNouvellesValeursDeStatut() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    service.lister(0, null, null, false);

    verify(jdbc)
        .query(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) ->
                    sql.contains("'a_jour'")
                        && sql.contains("'invalide'")
                        && !sql.contains("'a_verifier'")
                        && sql.contains("necessite_intervention_le")),
            anyMap(),
            any(RowMapper.class));
  }

  // Régression : facilite le repérage des candidats à corriger manuellement dans une pagination
  // volumineuse, sans devoir parcourir page par page.
  @Test
  @SuppressWarnings("unchecked")
  void lister_wikidataManquantVrai_filtreSurWikidataIdNull() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    service.lister(0, null, null, true);

    verify(jdbc).query(contains("wikidata_id IS NULL"), anyMap(), any(RowMapper.class));
  }

  // page au-delà du total (offset trop grand) : aucune ligne ne porte total_filtre, un second
  // aller-retour COUNT reste nécessaire pour ne pas afficher un total à zéro trompeur.
  @Test
  @SuppressWarnings("unchecked")
  void lister_pageAuDelaDuTotal_retombeSurUnCountSepare() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L);

    ReponseReferentielArtistes reponse = service.lister(5, null, null, false);

    assertEquals(1L, reponse.totalArtistes());
    assertTrue(reponse.artistes().isEmpty());
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

  // action minimale pour un admin qui juge la divergence de nom TM acceptable, sans
  // passer par une fusion.
  @Test
  void ignorerIntervention_artisteAvecSourceFlaggee_effaceEtLogueEtRetourneTrue() {
    UUID id = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    boolean maj = service.ignorerIntervention(id);

    assertTrue(maj);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) ->
                    sql.contains("necessite_intervention_le = NULL")
                        && sql.contains("nom_tm_ignore = nom_tm_incoherent")),
            anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), anyString(), anyMap());
  }

  @Test
  void ignorerIntervention_artisteInconnu_retourneFalseSansLog() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    assertFalse(service.ignorerIntervention(UUID.randomUUID()));
    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  // alternative à la fusion pour un simple mauvais lien (attractionId erroné).
  @Test
  void corrigerIdentifiantTm_artisteAvecSourceTicketmaster_metAJourEtReinitialiseLaSante() {
    UUID id = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    boolean maj = service.corrigerIdentifiantTm(id, "K8vZ917G1B7");

    assertTrue(maj);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) ->
                    sql.contains("identifiant_externe = :identifiant")
                        && sql.contains("nb_zero_consecutifs = 0")
                        && sql.contains("suspect_depuis = NULL")
                        && sql.contains("necessite_intervention_le = NULL")
                        && sql.contains("source = 'ticketmaster'")),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> "K8vZ917G1B7".equals(p.get("identifiant"))));
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), anyString(), anyMap());
  }

  @Test
  void corrigerIdentifiantTm_identifiantAvecEspaces_estTrimmeAvantEcriture() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    service.corrigerIdentifiantTm(UUID.randomUUID(), "  K8vZ917G1B7  ");

    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> "K8vZ917G1B7".equals(p.get("identifiant"))));
  }

  @Test
  void corrigerIdentifiantTm_artisteSansSourceTicketmaster_retourneFalse() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    assertFalse(service.corrigerIdentifiantTm(UUID.randomUUID(), "K8vZ917G1B7"));
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
    verify(jdbc, times(1))
        .update(contains("UPDATE artiste_sources SET necessite_intervention_le = NULL"), anyMap());
    verify(jdbc, times(1)).update(contains("UPDATE artistes SET"), anyMap());
    verify(jdbc, times(1)).update(contains("DELETE FROM artistes WHERE"), anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), any());
  }

  // deux artistes fusionnés peuvent chacun avoir enregistré le même concert réel (même
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
                        && sql.contains("c1.salle_id = c2.salle_id")
                        && sql.contains("c1.date = c2.date")),
            anyMap());
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
    when(jdbc.queryForList(contains("FROM artiste_sources src"), anyMap()))
        .thenReturn(List.of(conflit));

    boolean fusionne = service.fusionner(cible, source);

    assertTrue(fusionne);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) -> sql.contains("DELETE FROM artiste_sources WHERE artiste_id")),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p ->
                    p.get("artisteId").equals(source.toString())
                        && p.get("identifiantExterne").equals("tm-source-mort")));
  }
}
