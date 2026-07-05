/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import fr.strapontin.web.concert.FiltreConcerts;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires de {@link DecouverteService}.
///
/// <p>Couvre le filtre mois via bornes de dates (correction du {@code to_char()} non indexable,
/// méthode privée testée par réflexion) et le comportement global de {@link
/// DecouverteService#decouvrir}.
///
/// @since 2.0.5
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class DecouverteServiceTest {

  @Mock private NamedParameterJdbcTemplate jdbc;

  private DecouverteService service;

  @BeforeEach
  void setUp() {
    service = new DecouverteService(jdbc);
  }

  @Test
  void filtresMois_moisValide_poseBornesCorrectesEnParamsSql() throws Exception {
    Map<String, Object> params = new HashMap<>();
    invoquerConstruireFiltresWhere(service, "2026-05", params);
    org.junit.jupiter.api.Assertions.assertEquals(
        LocalDate.of(2026, 5, 1), params.get("moisDebut"));
    org.junit.jupiter.api.Assertions.assertEquals(LocalDate.of(2026, 6, 1), params.get("moisFin"));
  }

  @Test
  void filtresMois_decembre_moisFinEstJanvierAnneeSuivante() throws Exception {
    Map<String, Object> params = new HashMap<>();
    invoquerConstruireFiltresWhere(service, "2026-12", params);
    org.junit.jupiter.api.Assertions.assertEquals(
        LocalDate.of(2026, 12, 1), params.get("moisDebut"));
    org.junit.jupiter.api.Assertions.assertEquals(LocalDate.of(2027, 1, 1), params.get("moisFin"));
  }

  @Test
  void filtresMois_formatSansZeroLeading_filtreIgnore() throws Exception {
    Map<String, Object> params = new HashMap<>();
    invoquerConstruireFiltresWhere(service, "2026-5", params);
    org.junit.jupiter.api.Assertions.assertFalse(params.containsKey("moisDebut"));
    org.junit.jupiter.api.Assertions.assertFalse(params.containsKey("moisFin"));
  }

  @Test
  void filtresMois_vide_filtreIgnore() throws Exception {
    Map<String, Object> params = new HashMap<>();
    invoquerConstruireFiltresWhere(service, "", params);
    org.junit.jupiter.api.Assertions.assertFalse(params.containsKey("moisDebut"));
    org.junit.jupiter.api.Assertions.assertFalse(params.containsKey("moisFin"));
  }

  @SuppressWarnings("unchecked")
  private static void invoquerConstruireFiltresWhere(
      DecouverteService svc, String mois, Map<String, Object> params) throws Exception {
    var m =
        DecouverteService.class.getDeclaredMethod(
            "construireFiltresWhere",
            List.class,
            String.class,
            String.class,
            String.class,
            String.class,
            LocalDate.class,
            Map.class);
    m.setAccessible(true);
    m.invoke(svc, List.of(), "", mois, "", "", LocalDate.of(2026, 5, 29), params);
  }

  // --- decouvrir() ---

  @SuppressWarnings("unchecked")
  private void stubRequetesCommunes(List<String> deptsUtilisateur) {
    stubRequetesCommunes(deptsUtilisateur, 0);
  }

  @SuppressWarnings("unchecked")
  private void stubRequetesCommunes(List<String> deptsUtilisateur, int total) {
    when(jdbc.query(contains("departements FROM user_config"), anyMap(), any(RowMapper.class)))
        .thenReturn(
            deptsUtilisateur.isEmpty()
                ? List.of()
                : java.util.Collections.singletonList(deptsUtilisateur.toArray(String[]::new)));
    when(jdbc.query(contains("LEFT JOIN artistes"), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of());
    when(jdbc.queryForObject(contains("SELECT COUNT(*)"), anyMap(), eq(Integer.class)))
        .thenReturn(total);
    when(jdbc.queryForObject(
            contains("MAX(derniere_exec)"), anyMap(), eq(java.sql.Timestamp.class)))
        .thenReturn(null);
    when(jdbc.query(contains("GROUP BY dept"), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of());
    org.mockito.Mockito.doNothing()
        .when(jdbc)
        .query(
            contains("WITH base AS"),
            anyMap(),
            any(org.springframework.jdbc.core.RowCallbackHandler.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void decouvrir_deptsNullDansFiltre_utiliseDepartementsUtilisateur() {
    stubRequetesCommunes(List.of("75", "92"));
    FiltreConcerts filtre =
        new FiltreConcerts("", "", "", "", "date", 0, false, false, null, null, null);

    ReponseDecouverte rep = service.decouvrir("user-1", filtre);

    assertThat(rep.deptsUtilisateur()).containsExactly("75", "92");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .queryForObject(sqlCaptor.capture(), anyMap(), eq(Integer.class));
    assertThat(sqlCaptor.getValue()).contains("dept = ANY(:depts)");
  }

  @Test
  @SuppressWarnings("unchecked")
  void decouvrir_deptsFournisDansFiltre_prevalentSurDepartementsUtilisateur() {
    stubRequetesCommunes(List.of("75"));
    FiltreConcerts filtre =
        new FiltreConcerts(
            "", "", "", "", "date", 0, false, false, null, null, List.of("33", "44"));

    service.decouvrir("user-1", filtre);

    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .queryForObject(contains("SELECT COUNT(*)"), paramsCaptor.capture(), eq(Integer.class));
    assertThat((String[]) paramsCaptor.getValue().get("depts")).containsExactly("33", "44");
  }

  @Test
  @SuppressWarnings("unchecked")
  void decouvrir_triNouveaute_appliqueLaClauseTriCorrespondante() {
    stubRequetesCommunes(List.of());
    FiltreConcerts filtre =
        new FiltreConcerts("", "", "", "", "nouveaute", 0, false, false, null, null, List.of("75"));

    service.decouvrir("user-1", filtre);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .query(sqlCaptor.capture(), anyMap(), any(RowMapper.class));
    assertThat(sqlCaptor.getAllValues())
        .anyMatch(sql -> sql.contains("ORDER BY c.first_seen DESC, c.date ASC"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void decouvrir_pageDeuxAvecTotal250_calculeOffsetEtTotalPages() {
    stubRequetesCommunes(List.of(), 250);
    FiltreConcerts filtre =
        new FiltreConcerts("", "", "", "", "date", 2, false, false, null, null, List.of("75"));

    ReponseDecouverte rep = service.decouvrir("user-1", filtre);

    assertThat(rep.page()).isEqualTo(2);
    assertThat(rep.totalConcerts()).isEqualTo(250);
    assertThat(rep.totalPages()).isEqualTo(3);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    org.mockito.Mockito.verify(jdbc)
        .query(contains("LEFT JOIN artistes"), paramsCaptor.capture(), any(RowMapper.class));
    assertThat(paramsCaptor.getValue().get("offset")).isEqualTo(200);
  }

  @Test
  @SuppressWarnings("unchecked")
  void decouvrir_pageNegative_estRameneeAZero() {
    stubRequetesCommunes(List.of());
    FiltreConcerts filtre =
        new FiltreConcerts("", "", "", "", "date", -5, false, false, null, null, List.of("75"));

    ReponseDecouverte rep = service.decouvrir("user-1", filtre);

    assertThat(rep.page()).isEqualTo(0);
  }
}
