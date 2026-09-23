/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link AdminUtilisateursListingService} (pagination des utilisateurs,
 * dépendances mockées).
 *
 * @since 2.9.1
 * @author Kwaite
 */
class AdminUtilisateursListingServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private AdminUtilisateursListingService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    service = new AdminUtilisateursListingService(jdbc);
  }

  // garde-fou contre une croissance non bornée de la table users : le total vient de la même
  // exécution que la page (COUNT(*) OVER()), pas d'une requête COUNT séparée.
  @Test
  void lister_nominal_retournePageAvecTotal() {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("id", "u1");
    ligne.put("email", "a@b.fr");
    ligne.put("role", "user");
    ligne.put("username", "user1");
    ligne.put("display_username", "Utilisateur Un");
    ligne.put("must_change_credentials", false);
    ligne.put("created_at", Timestamp.from(Instant.now()));
    ligne.put("revocation_admin_le", null);
    ligne.put("verrouille", false);
    ligne.put("nb_salles", 2);
    ligne.put("nb_artistes", 0);
    ligne.put("total_filtre", 3L);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));

    final ReponseUtilisateursAdmin reponse = service.lister(0);

    assertEquals(1, reponse.utilisateurs().size());
    assertEquals(3L, reponse.total());
    assertEquals(0, reponse.page());
  }

  // page au-delà du total (offset trop grand) : aucune ligne ne porte total_filtre, un second
  // aller-retour COUNT reste nécessaire pour ne pas afficher un total à zéro trompeur.
  @Test
  void lister_pageAuDelaDuTotal_retombeSurUnCountSepare() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(5L);

    final ReponseUtilisateursAdmin reponse = service.lister(3);

    assertEquals(5L, reponse.total());
    assertTrue(reponse.utilisateurs().isEmpty());
  }
}
