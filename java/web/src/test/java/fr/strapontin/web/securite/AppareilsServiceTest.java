/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link AppareilsService}.
 *
 * @since 2.7.8
 * @author Kwaite
 */
class AppareilsServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private AppareilsService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    service = new AppareilsService(jdbc);
  }

  @Test
  void enregistrerAppareil_delegueAuJdbc() {
    service.enregistrerAppareil("sess-1", "u1", "Mozilla/5.0", "navigateur");

    org.mockito.Mockito.verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO session_appareils"), anyMap());
  }

  // La ligne SPRING_SESSION référencée n'existe pas toujours encore au moment de cet appel
  // (voir AppareilEnregistrementInterceptor) : une violation de contrainte ne doit jamais remonter.
  @Test
  void enregistrerAppareil_echecJdbc_neLevePasDException() {
    when(jdbc.update(anyString(), anyMap())).thenThrow(new DataIntegrityViolationException("fk"));

    assertDoesNotThrow(
        () -> service.enregistrerAppareil("sess-1", "u1", "Mozilla/5.0", "navigateur"));
  }

  // L'identifiant exposé côté client (appareilId) doit être l'UUID opaque, jamais le session_id
  // réel — vérifié en s'assurant que sessionCourante est déterminé par comparaison interne au
  // session_id, sans jamais renvoyer ce dernier dans le DTO.
  @Test
  @SuppressWarnings("unchecked")
  void listerAppareils_marqueLaSessionCouranteEtNExposePasLeSessionId() {
    final UUID appareilId = UUID.randomUUID();
    when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              final RowMapper<AppareilsService.AppareilDto> mapper = invocation.getArgument(2);
              final ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("appareil_id", UUID.class)).thenReturn(appareilId);
              when(rs.getString("session_id")).thenReturn("sess-courante");
              when(rs.getString("user_agent")).thenReturn("Mozilla/5.0");
              when(rs.getLong("creation_time")).thenReturn(1000L);
              when(rs.getLong("last_access_time")).thenReturn(2000L);
              return List.of(mapper.mapRow(rs, 0));
            });

    final List<AppareilsService.AppareilDto> resultat =
        service.listerAppareils("u1", "sess-courante");

    assertEquals(1, resultat.size());
    assertEquals(appareilId.toString(), resultat.get(0).appareilId());
    assertTrue(resultat.get(0).sessionCourante());
  }

  @Test
  void revoquerAppareil_aucuneLigneSupprimee_retourneFalse() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    assertFalse(service.revoquerAppareil("u1", UUID.randomUUID().toString()));
  }

  @Test
  void revoquerAppareil_ligneSupprimee_retourneTrue() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    assertTrue(service.revoquerAppareil("u1", UUID.randomUUID().toString()));
  }

  private static final String SAFARI_27_0_1 =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like"
          + " Gecko) Version/27.0.1 Mobile/15E148 Safari/604.1";
  private static final String SAFARI_27_0_2 = SAFARI_27_0_1.replace("27.0.1", "27.0.2");
  private static final String FIREFOX_LINUX =
      "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0";

  private void appareilsConnus(final String userAgent, final String mode) {
    final Map<String, Object> ligne = new java.util.HashMap<>();
    ligne.put("user_agent", userAgent);
    ligne.put("mode_affichage", mode);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
  }

  @Test
  void estNouvelAppareil_aucunAppareilConnu_retourneFalse() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    assertFalse(service.estNouvelAppareil("u1", SAFARI_27_0_1, "navigateur"));
  }

  @Test
  void estNouvelAppareil_navigateurMisAJour_retourneFalse() {
    appareilsConnus(SAFARI_27_0_1, "NAVIGATEUR");

    assertFalse(service.estNouvelAppareil("u1", SAFARI_27_0_2, "navigateur"));
  }

  @Test
  void estNouvelAppareil_sessionAnterieureSansMode_neDistingueQueNavigateurEtSysteme() {
    appareilsConnus(SAFARI_27_0_1, null);

    assertFalse(service.estNouvelAppareil("u1", SAFARI_27_0_1, "pwa"));
  }

  @Test
  void estNouvelAppareil_autreNavigateurOuSysteme_retourneTrue() {
    appareilsConnus(SAFARI_27_0_1, "NAVIGATEUR");

    assertTrue(service.estNouvelAppareil("u1", FIREFOX_LINUX, "navigateur"));
  }

  @Test
  void estNouvelAppareil_memeAppareilEnModePwaJamaisVu_retourneTrue() {
    appareilsConnus(SAFARI_27_0_1, "NAVIGATEUR");

    assertTrue(service.estNouvelAppareil("u1", SAFARI_27_0_1, "pwa"));
  }

  @Test
  void estNouvelAppareil_userAgentIllisibleInedit_retourneTrue() {
    appareilsConnus("curl/8.4", "INCONNU");

    assertTrue(service.estNouvelAppareil("u1", "wget/1.21", null));
  }

  @Test
  void estNouvelAppareil_echecJdbc_retourneFalseSansException() {
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenThrow(new DataIntegrityViolationException("x"));

    assertDoesNotThrow(() -> assertFalse(service.estNouvelAppareil("u1", "Mozilla/5.0", null)));
  }
}
