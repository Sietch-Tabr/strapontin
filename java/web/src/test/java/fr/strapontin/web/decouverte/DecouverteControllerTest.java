/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.decouverte;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires de {@link DecouverteController}.
///
/// <p>Couvre le filtre mois via bornes de dates (correction du {@code to_char()} non indexable).
///
/// @since 2.0.5
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class DecouverteControllerTest {

  @Mock private NamedParameterJdbcTemplate jdbc;

  private DecouverteController controller;

  @BeforeEach
  void setUp() {
    controller = new DecouverteController(jdbc);
  }

  @Test
  void filtresMois_moisValide_poseBornesCorrectesEnParamsSql() throws Exception {
    Map<String, Object> params = new HashMap<>();
    invoquerConstruireFiltresWhere(controller, "2026-05", params);
    assertEquals(LocalDate.of(2026, 5, 1), params.get("moisDebut"));
    assertEquals(LocalDate.of(2026, 6, 1), params.get("moisFin"));
  }

  @Test
  void filtresMois_decembre_moisFinEstJanvierAnneeSuivante() throws Exception {
    Map<String, Object> params = new HashMap<>();
    invoquerConstruireFiltresWhere(controller, "2026-12", params);
    assertEquals(LocalDate.of(2026, 12, 1), params.get("moisDebut"));
    assertEquals(LocalDate.of(2027, 1, 1), params.get("moisFin"));
  }

  @Test
  void filtresMois_formatSansZeroLeading_filtreIgnore() throws Exception {
    Map<String, Object> params = new HashMap<>();
    invoquerConstruireFiltresWhere(controller, "2026-5", params);
    assertFalse(params.containsKey("moisDebut"));
    assertFalse(params.containsKey("moisFin"));
  }

  @Test
  void filtresMois_vide_filtreIgnore() throws Exception {
    Map<String, Object> params = new HashMap<>();
    invoquerConstruireFiltresWhere(controller, "", params);
    assertFalse(params.containsKey("moisDebut"));
    assertFalse(params.containsKey("moisFin"));
  }

  @SuppressWarnings("unchecked")
  private static void invoquerConstruireFiltresWhere(
      DecouverteController ctrl, String mois, Map<String, Object> params) throws Exception {
    var m =
        DecouverteController.class.getDeclaredMethod(
            "construireFiltresWhere",
            List.class,
            String.class,
            String.class,
            String.class,
            String.class,
            LocalDate.class,
            Map.class);
    m.setAccessible(true);
    m.invoke(ctrl, List.of(), "", mois, "", "", LocalDate.of(2026, 5, 29), params);
  }
}
