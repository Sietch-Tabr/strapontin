/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests unitaires pour {@link StockageCompteurResend}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class StockageCompteurResendTest {

  private NamedParameterJdbcTemplate jdbc;
  private StockageCompteurResend stockage;

  @BeforeEach
  void preparer() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    stockage = new StockageCompteurResend(new AccesAppConfig(jdbc), JsonMapper.builder().build());
  }

  private void stocker(final String json) {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of(json));
  }

  @Test
  void lireBrut_absent_retourneVide() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());
    assertEquals(Optional.empty(), stockage.lireBrut());
  }

  @Test
  void lireBrut_jsonValide_retourneLeCompteur() {
    stocker(
        "{\"jour\":\"2026-11-17\",\"envoyesJour\":3,\"mois\":\"2026-11\",\"envoyesMois\":42,"
            + "\"alerteJourEnvoyee\":false,\"alerteMoisEnvoyee\":true}");
    final CompteurEnvoisResend compteur = stockage.lireBrut().orElseThrow();
    assertEquals(3, compteur.envoyesJour());
    assertEquals(42, compteur.envoyesMois());
    assertTrue(compteur.alerteMoisEnvoyee());
  }

  @Test
  void lireBrut_jsonMalForme_retourneVideSansEchouer() {
    stocker("pas du json {");
    assertEquals(Optional.empty(), stockage.lireBrut());
  }

  @Test
  void ecrire_stockeLeCompteurEnJsonSousLaCleDuQuota() {
    stockage.ecrire(new CompteurEnvoisResend("2026-11-17", 1, "2026-11", 2, false, false));
    final ArgumentCaptor<Map<String, ?>> parametres = ArgumentCaptor.forClass(Map.class);
    verify(jdbc).update(anyString(), parametres.capture());
    assertEquals(RegleQuotaResend.CLE_QUOTA, parametres.getValue().get("cle"));
    assertTrue(((String) parametres.getValue().get("valeur")).contains("\"envoyesMois\":2"));
  }
}
