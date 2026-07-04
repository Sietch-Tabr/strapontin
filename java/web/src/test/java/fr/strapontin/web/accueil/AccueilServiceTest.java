/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.web.utilisateur.UserConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
}
