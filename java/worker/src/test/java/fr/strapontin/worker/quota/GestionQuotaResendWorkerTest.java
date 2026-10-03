/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.quota;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.CompteurEnvoisResend;
import fr.strapontin.worker.LogsAdminWorker;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires de {@link GestionQuotaResendWorker} (suite de l'incident).
 *
 * @since 2.7.2
 * @author Kwaite
 */
class GestionQuotaResendWorkerTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogsAdminWorker logsAdminWorker;
  private GestionQuotaResendWorker gestionQuotaResendWorker;

  private static String jourCourant() {
    return LocalDate.now(ZoneOffset.UTC).toString();
  }

  private static String moisCourant() {
    return jourCourant().substring(0, 7);
  }

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logsAdminWorker = mock(LogsAdminWorker.class);
    gestionQuotaResendWorker =
        new GestionQuotaResendWorker(jdbc, new ObjectMapper(), logsAdminWorker);
    gestionQuotaResendWorker.setSelf(gestionQuotaResendWorker);
  }

  private void enBase(final CompteurEnvoisResend compteur) throws Exception {
    final String json = new ObjectMapper().writeValueAsString(compteur);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(String.class))).thenReturn(json);
  }

  @Test
  void reserverEnvoi_aucunCompteurEnBase_autorise() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(String.class)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertTrue(gestionQuotaResendWorker.reserverEnvoi());
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void reserverEnvoi_sousLaLimite_autorise() throws Exception {
    enBase(new CompteurEnvoisResend(jourCourant(), 5, moisCourant(), 50, false, false));

    assertTrue(gestionQuotaResendWorker.reserverEnvoi());
  }

  @Test
  void reserverEnvoi_limiteJourAtteinte_bloqueEtInsereLogAdminError() throws Exception {
    enBase(new CompteurEnvoisResend(jourCourant(), 100, moisCourant(), 50, false, false));

    final boolean autorise = gestionQuotaResendWorker.reserverEnvoi();

    assertFalse(autorise);
    verify(logsAdminWorker).inserer(eq("error"), eq("worker"), anyString(), anyMap());
  }

  @Test
  void reserverEnvoi_limiteMoisAtteinte_bloqueEtInsereLogAdminError() throws Exception {
    enBase(new CompteurEnvoisResend(jourCourant(), 5, moisCourant(), 3000, false, false));

    final boolean autorise = gestionQuotaResendWorker.reserverEnvoi();

    assertFalse(autorise);
    verify(logsAdminWorker).inserer(eq("error"), eq("worker"), anyString(), anyMap());
  }

  @Test
  void reserverEnvoi_jourChange_reinitialiseLeCompteurJour() throws Exception {
    enBase(new CompteurEnvoisResend("2020-01-01", 99, moisCourant(), 500, true, false));

    assertTrue(gestionQuotaResendWorker.reserverEnvoi());
  }

  @Test
  void reserverEnvoi_compteurMalforme_autoriseCommeUnCompteurInitial() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(String.class)))
        .thenReturn("{pas-du-json-valide");

    assertTrue(gestionQuotaResendWorker.reserverEnvoi());
  }
}
