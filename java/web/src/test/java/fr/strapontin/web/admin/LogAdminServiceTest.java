/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link LogAdminService}.
 *
 * @since 2.8.7
 * @author Kwaite
 */
class LogAdminServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogAdminService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    service = new LogAdminService(jdbc, new ObjectMapper());
  }

  // garde-fou contre une croissance non bornée du lot lu par le digest email : un retard
  // important est absorbé sur plusieurs cycles de 15 min plutôt que de charger un lot non borné.
  @Test
  @SuppressWarnings("unchecked")
  void lireErreursDepuis_plafonneLeLotAMilleEntrees() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    service.lireErreursDepuis(1000L);

    verify(jdbc)
        .query(
            contains("LIMIT :limite"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p ->
                    Integer.valueOf(1000).equals(p.get("limite"))
                        && p.get("tsDepuis").equals(1000L)),
            any(RowMapper.class));
  }

  @Test
  void logAdmin_sansMeta_neLevePasException() {
    org.assertj.core.api.Assertions.assertThatCode(
            () -> service.logAdmin("info", "test", "message sans meta"))
        .doesNotThrowAnyException();
  }
}
