/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.utilisateur.UserConfigService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires de {@link AccueilService}.
///
/// <p>Couvre la restriction aux matchs artiste de {@link AccueilService#marquerVus} (#799).
///
/// @since 2.6.3
/// @author Kwaite
class AccueilServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private AccueilService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    service = new AccueilService(jdbc, mock(UserConfigService.class));
  }

  // #799 : marquerVus effaçait à tort le badge "Nouveau" des matchs salle (jamais affichés sur
  // l'accueil, uniquement sur /spectacles) car la mise à jour ne filtrait pas par source de
  // match. Le SQL doit désormais restreindre aux concerts dont l'artiste est suivi.
  @Test
  void marquerVus_construitUneRequeteRestreinteAuxMatchsArtiste() {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.marquerVus("user-1");

    verify(jdbc).update(sqlCaptor.capture(), anyMap());
    String sql = sqlCaptor.getValue();
    assertTrue(sql.contains("user_artistes"), "la requête doit filtrer via user_artistes");
    assertTrue(sql.contains("vu_le IS NULL"), "seuls les matchs non vus doivent être mis à jour");
  }

  // #816 : le badge "Nouveau" par concert (est_nouveau) doit être unifié sur um.vu_le IS NULL
  // (cohérent avec SpectacleDto.estNew et l'intention "jamais vu"), pas sur first_seen (fraîcheur
  // en base) — sinon un concert ajouté il y a 10 jours et jamais vu n'affichait pas "Nouveau" sur
  // l'accueil alors qu'il l'affichait sur /spectacles.
  @Test
  @SuppressWarnings("unchecked")
  void accueil_calculEstNouveau_utiliseVuLeIsNullPasFirstSeen() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.accueil("user-1", false, null, false);

    verify(jdbc).query(sqlCaptor.capture(), anyMap(), any(RowMapper.class));
    String sql = sqlCaptor.getValue();
    assertTrue(
        sql.contains("um.vu_le IS NULL AS est_nouveau"),
        "est_nouveau doit être calculé via um.vu_le IS NULL");
    assertFalse(
        sql.contains("c.first_seen >= (CURRENT_TIMESTAMP - INTERVAL '7 days') AS est_nouveau"),
        "l'ancien calcul basé sur first_seen ne doit plus alimenter est_nouveau");
  }
}
