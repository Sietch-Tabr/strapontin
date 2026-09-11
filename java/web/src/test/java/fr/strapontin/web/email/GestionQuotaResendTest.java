/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.CompteurEnvoisResend;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tests unitaires de {@link GestionQuotaResend} (suite de l'incident).
 *
 * @since 2.7.2
 * @author Kwaite
 */
class GestionQuotaResendTest {

  private JdbcTemplate jdbc;
  private GestionQuotaResend gestionQuotaResend;

  private static String jourCourant() {
    return LocalDate.now(ZoneOffset.UTC).toString();
  }

  private static String moisCourant() {
    return jourCourant().substring(0, 7);
  }

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    gestionQuotaResend = new GestionQuotaResend(jdbc, new ObjectMapper());
  }

  private void enBase(final CompteurEnvoisResend compteur) throws Exception {
    final String json = new ObjectMapper().writeValueAsString(compteur);
    when(jdbc.queryForObject(anyString(), eq(String.class), eq("RESEND_QUOTA_JSON")))
        .thenReturn(json);
  }

  // --- reserverEnvoi ---

  @Test
  void reserverEnvoi_aucunCompteurEnBase_autoriseEtInitialise() {
    when(jdbc.queryForObject(anyString(), eq(String.class), eq("RESEND_QUOTA_JSON")))
        .thenThrow(new EmptyResultDataAccessException(1));

    final boolean autorise = gestionQuotaResend.reserverEnvoi();

    assertTrue(autorise);
    verify(jdbc).update(anyString(), eq("RESEND_QUOTA_JSON"), anyString(), anyString());
  }

  @Test
  void reserverEnvoi_sousLaLimite_autoriseEtIncremente() throws Exception {
    enBase(new CompteurEnvoisResend(jourCourant(), 5, moisCourant(), 50, false, false));

    final boolean autorise = gestionQuotaResend.reserverEnvoi();

    assertTrue(autorise);
  }

  @Test
  void reserverEnvoi_limiteJourAtteinte_bloque() throws Exception {
    enBase(
        new CompteurEnvoisResend(
            jourCourant(), GestionQuotaResend.LIMITE_JOUR, moisCourant(), 50, false, false));

    final boolean autorise = gestionQuotaResend.reserverEnvoi();

    assertFalse(autorise);
  }

  @Test
  void reserverEnvoi_limiteMoisAtteinte_bloque() throws Exception {
    enBase(
        new CompteurEnvoisResend(
            jourCourant(), 5, moisCourant(), GestionQuotaResend.LIMITE_MOIS, false, false));

    final boolean autorise = gestionQuotaResend.reserverEnvoi();

    assertFalse(autorise);
  }

  @Test
  void reserverEnvoi_jourChange_reinitialiseLeCompteurJourSansToucherAuMois() throws Exception {
    enBase(new CompteurEnvoisResend("2020-01-01", 99, moisCourant(), 500, true, false));

    final boolean autorise = gestionQuotaResend.reserverEnvoi();

    assertTrue(autorise, "un ancien jour épuisé ne doit pas bloquer le jour courant");
  }

  @Test
  void reserverEnvoi_moisChange_reinitialiseLeCompteurMoisSansToucherAuJour() throws Exception {
    enBase(new CompteurEnvoisResend(jourCourant(), 5, "2020-01", 2999, false, true));

    final boolean autorise = gestionQuotaResend.reserverEnvoi();

    assertTrue(autorise);
  }

  // --- seuilFranchiNonSignale / marquerAlerteEnvoyee ---

  @Test
  void seuilFranchiNonSignale_sousLeSeuil_retourneFalse() {
    final CompteurEnvoisResend compteur =
        new CompteurEnvoisResend(jourCourant(), 10, moisCourant(), 100, false, false);

    assertFalse(gestionQuotaResend.seuilFranchiNonSignale(compteur));
  }

  @Test
  void seuilFranchiNonSignale_seuilJourFranchiNonSignale_retourneTrue() {
    final CompteurEnvoisResend compteur =
        new CompteurEnvoisResend(jourCourant(), 85, moisCourant(), 100, false, false);

    assertTrue(gestionQuotaResend.seuilFranchiNonSignale(compteur));
  }

  @Test
  void seuilFranchiNonSignale_seuilJourFranchiDejaSignale_retourneFalse() {
    final CompteurEnvoisResend compteur =
        new CompteurEnvoisResend(jourCourant(), 85, moisCourant(), 100, true, false);

    assertFalse(gestionQuotaResend.seuilFranchiNonSignale(compteur));
  }

  @Test
  void seuilFranchiNonSignale_seuilMoisFranchiNonSignale_retourneTrue() {
    final CompteurEnvoisResend compteur =
        new CompteurEnvoisResend(jourCourant(), 10, moisCourant(), 2500, false, false);

    assertTrue(gestionQuotaResend.seuilFranchiNonSignale(compteur));
  }

  @Test
  void marquerAlerteEnvoyee_seuilJourFranchi_persisteAlerteJourEnvoyee() throws Exception {
    final CompteurEnvoisResend compteur =
        new CompteurEnvoisResend(jourCourant(), 85, moisCourant(), 100, false, false);

    gestionQuotaResend.marquerAlerteEnvoyee(compteur);

    final org.mockito.ArgumentCaptor<String> jsonCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(anyString(), eq("RESEND_QUOTA_JSON"), jsonCaptor.capture(), anyString());
    final CompteurEnvoisResend persiste =
        new ObjectMapper().readValue(jsonCaptor.getValue(), CompteurEnvoisResend.class);
    assertTrue(persiste.alerteJourEnvoyee());
    assertFalse(persiste.alerteMoisEnvoyee());
  }

  @Test
  void lireCompteurCourant_compteurMalforme_retourneCompteurInitial() {
    when(jdbc.queryForObject(anyString(), eq(String.class), eq("RESEND_QUOTA_JSON")))
        .thenReturn("{pas-du-json-valide");

    final CompteurEnvoisResend compteur = gestionQuotaResend.lireCompteurCourant();

    assertEquals(0, compteur.envoyesJour());
    assertEquals(0, compteur.envoyesMois());
  }
}
