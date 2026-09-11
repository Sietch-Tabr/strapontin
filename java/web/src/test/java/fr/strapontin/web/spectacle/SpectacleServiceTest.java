/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.concert.FiltreConcerts;
import fr.strapontin.web.utilisateur.UserConfigService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link SpectacleService}.
 *
 * @since 2.2.0
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class SpectacleServiceTest {

  @Mock private NamedParameterJdbcTemplate jdbc;
  @Mock private UserConfigService userConfigService;

  private SpectacleService service;

  @BeforeEach
  void setUp() {
    service = new SpectacleService(jdbc, userConfigService);
  }

  private static FiltreConcerts filtre(
      String q,
      String mois,
      String classification,
      String genre,
      boolean quinzaine,
      String tri,
      boolean masques,
      List<String> sources,
      String indisponibles,
      int page) {
    return new FiltreConcerts(
        q,
        mois,
        genre,
        classification,
        tri,
        page,
        quinzaine,
        masques,
        sources,
        indisponibles,
        null);
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_sansFiltre_retournePremierePage() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(5);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(2);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    ReponseSpectacles rep =
        service.listerSpectacles("u1", filtre("", "", "", "", false, "date", false, null, null, 0));

    assertNotNull(rep);
    assertEquals(0, rep.page());
    assertEquals(5, rep.totalConcerts());
    assertEquals(1, rep.totalPages());
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_avecQuinzaine_ajouteConditionDans15Jours() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

    service.listerSpectacles("u1", filtre("", "", "", "", true, "date", false, null, null, 0));

    verify(jdbc).query(contains("dans15Jours"), captor.capture(), any(RowMapper.class));
    assertTrue(captor.getValue().containsKey("dans15Jours"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_sourceArtistesSeuls_filtreParUuids() {
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.queryForList(contains("user_artistes WHERE user_id"), anyMap(), eq(UUID.class)))
        .thenReturn(List.of(id1, id2));
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(2);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    service.listerSpectacles(
        "u1", filtre("", "", "", "", false, "date", false, List.of("artistes"), null, 0));

    verify(jdbc, atLeastOnce())
        .queryForList(contains("user_artistes WHERE user_id"), anyMap(), eq(UUID.class));
    verify(jdbc).query(contains("artisteIds"), anyMap(), any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_sourceArtistesVide_ajouteFalse() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.queryForList(contains("user_artistes WHERE user_id"), anyMap(), eq(UUID.class)))
        .thenReturn(List.of());
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    service.listerSpectacles(
        "u1", filtre("", "", "", "", false, "date", false, List.of("artistes"), null, 0));

    verify(jdbc).query(contains("AND false"), anyMap(), any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_triNouveaute_utiliseOrderByFirstSeen() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.query(contains("first_seen DESC"), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    service.listerSpectacles(
        "u1", filtre("", "", "", "", false, "nouveaute", false, null, null, 0));

    verify(jdbc).query(contains("first_seen DESC"), anyMap(), any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_pageDeux_calculeOffsetCorrect() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(250);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(1);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

    service.listerSpectacles("u1", filtre("", "", "", "", false, "date", false, null, null, 2));

    verify(jdbc).query(contains("LIMIT"), captor.capture(), any(RowMapper.class));
    assertEquals(200, captor.getValue().get("offset"));
  }

  @Test
  void modifierMatch_masquer_executeUpdateAvecMasqueLe() {
    service.modifierMatch("u1", "concert-uuid-123", true);

    verify(jdbc).update(contains("masque_le = now()"), anyMap());
  }

  @Test
  void modifierMatch_demasquer_executeUpdateAvecNull() {
    service.modifierMatch("u1", "concert-uuid-123", false);

    verify(jdbc).update(contains("masque_le = NULL"), anyMap());
  }

  // filtre désormais sur artiste_id (référentiel, stable) plutôt qu'artiste_norm (texte
  // par source, pouvant diverger entre concerts d'un même artiste réel).
  @Test
  @SuppressWarnings("unchecked")
  void masquerArtiste_executeMiseAJourAvecArtisteId() {
    java.util.UUID artisteId = java.util.UUID.randomUUID();

    service.masquerArtiste("u1", artisteId);

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) -> sql.contains("masque_le = now()") && sql.contains("artiste_id")),
            captor.capture());
    assertEquals(artisteId.toString(), captor.getValue().get("artisteId"));
    assertEquals("u1", captor.getValue().get("userId"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_sourceSallesSeules_filtreParSalleIds() {
    UUID id1 = UUID.randomUUID();
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.queryForList(contains("user_salles WHERE user_id"), anyMap(), eq(UUID.class)))
        .thenReturn(List.of(id1));
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    service.listerSpectacles(
        "u1", filtre("", "", "", "", false, "date", false, List.of("salles"), null, 0));

    verify(jdbc, atLeastOnce())
        .queryForList(contains("user_salles WHERE user_id"), anyMap(), eq(UUID.class));
    verify(jdbc).query(contains("salleIds"), anyMap(), any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_sourceSallesVide_ajouteFalse() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.queryForList(contains("user_salles WHERE user_id"), anyMap(), eq(UUID.class)))
        .thenReturn(List.of());
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    service.listerSpectacles(
        "u1", filtre("", "", "", "", false, "date", false, List.of("salles"), null, 0));

    verify(jdbc).query(contains("AND false"), anyMap(), any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_rechercheQ_ajouteConditionIlike() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

    service.listerSpectacles(
        "u1", filtre("radiohead", "", "", "", false, "date", false, null, null, 0));

    verify(jdbc)
        .query(contains("c.artiste ILIKE :recherche"), captor.capture(), any(RowMapper.class));
    assertEquals("%radiohead%", captor.getValue().get("recherche"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_genreEtClassification_ajoutentLesConditions() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    service.listerSpectacles(
        "u1", filtre("", "", "concert", "Pop", false, "date", false, null, null, 0));

    verify(jdbc)
        .query(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) ->
                    sql.contains("c.genre ILIKE :genre")
                        && sql.contains("c.classification = :classification")),
            anyMap(),
            any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_masquesVrai_filtreSurMasqueLeNotNull() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    service.listerSpectacles("u1", filtre("", "", "", "", false, "date", true, null, null, 0));

    verify(jdbc).query(contains("um.masque_le IS NOT NULL"), anyMap(), any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_indisponiblesExplicite1_incluTousLesStatuts_sansRestriction() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(true);
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    ReponseSpectacles rep =
        service.listerSpectacles("u1", filtre("", "", "", "", false, "date", false, null, "1", 0));

    assertTrue(rep.inclureIndisponibles());
    verify(jdbc, org.mockito.Mockito.never())
        .query(contains("c.statut IS NULL OR c.statut IN"), anyMap(), any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_indisponiblesExplicite0_ajouteLaRestrictionMemeSiPreferenceContraire() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(true);
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    ReponseSpectacles rep =
        service.listerSpectacles("u1", filtre("", "", "", "", false, "date", false, null, "0", 0));

    assertFalse(rep.inclureIndisponibles());
    verify(jdbc).query(contains("c.statut IS NULL OR c.statut IN"), anyMap(), any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_indisponiblesNull_repliSurLireFiltreDisponibles() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(true);
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    doNothing().when(jdbc).query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    ReponseSpectacles rep =
        service.listerSpectacles("u1", filtre("", "", "", "", false, "date", false, null, null, 0));

    assertFalse(rep.inclureIndisponibles(), "préférence disponibles seuls → indisponibles exclus");
  }

  @Test
  @SuppressWarnings("unchecked")
  void listerSpectacles_filtresDisponibles_contenuReellementVerifie() {
    when(userConfigService.lireFiltreDisponibles("u1")).thenReturn(false);
    when(jdbc.query(contains("LIMIT"), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("COUNT(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(0);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              RowCallbackHandler handler = invocation.getArgument(2);
              handler.processRow(ligneFiltre("genre", "Rock"));
              handler.processRow(ligneFiltre("mois", "2026-05"));
              handler.processRow(ligneFiltre("classification", "concert"));
              return null;
            })
        .when(jdbc)
        .query(contains("filtres_base"), anyMap(), any(RowCallbackHandler.class));

    ReponseSpectacles rep =
        service.listerSpectacles("u1", filtre("", "", "", "", false, "date", false, null, null, 0));

    assertEquals(List.of("Rock"), rep.genresDisponibles());
    assertEquals(List.of("2026-05"), rep.moisDisponibles());
    assertEquals(List.of("concert"), rep.classificationsDisponibles());
  }

  private static java.sql.ResultSet ligneFiltre(String type, String valeur)
      throws java.sql.SQLException {
    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
    when(rs.getString("type")).thenReturn(type);
    when(rs.getString("valeur")).thenReturn(valeur);
    return rs;
  }

  @Test
  @SuppressWarnings("unchecked")
  void demasquerArtiste_executeMiseAJourAvecArtisteId() {
    java.util.UUID artisteId = java.util.UUID.randomUUID();

    service.demasquerArtiste("u1", artisteId);

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.argThat(
                (String sql) -> sql.contains("masque_le = NULL") && sql.contains("artiste_id")),
            captor.capture());
    assertEquals(artisteId.toString(), captor.getValue().get("artisteId"));
  }
}
