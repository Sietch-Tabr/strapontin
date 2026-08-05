/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires de {@link ReconciliateurArtistes} — priorité exacte avant flou.
///
/// @since 2.6.3
/// @author Kwaite
class ReconciliateurArtistesTest {

  private static ValeurConcert concert(String artisteNorm, LocalDate date, String salleNorm) {
    return new ValeurConcert(
        "Artiste",
        artisteNorm,
        date,
        null,
        "Salle",
        salleNorm,
        null,
        null,
        null,
        "ticketmaster",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  // Cas réel documenté (wiki Sources-de-données) : "krash karma" / "krashkarma" donnent un Jaro
  // ≈ 0.97 ≥ 0.95 (doublon flou). Sans ORDER BY sur la requête source, le candidat flou peut
  // apparaître avant le candidat exact dans le result set — avant, la boucle s'arrêtait sur
  // le premier match flou trouvé, ratant le candidat exact plus loin dans la même liste.
  @Test
  void reconcilierArtistes_candidatFlouAvantCandidatExact_prioriseLexact() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);

    LocalDate date = LocalDate.of(2025, 6, 15);
    UUID idFlou = UUID.randomUUID();
    UUID idExact = UUID.randomUUID();

    Map<String, Object> candidatFlou = new HashMap<>();
    candidatFlou.put("artiste_norm", "krashkarma");
    candidatFlou.put("artiste_id", idFlou);
    candidatFlou.put("date", date.toString());
    candidatFlou.put("salle_norm", "zenith paris");

    Map<String, Object> candidatExact = new HashMap<>();
    candidatExact.put("artiste_norm", "krash karma");
    candidatExact.put("artiste_id", idExact);
    candidatExact.put("date", date.toString());
    candidatExact.put("salle_norm", "zenith paris");

    // Ordre volontairement défavorable : flou avant exact.
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(candidatFlou, candidatExact));

    ValeurConcert entrant = concert("krash karma", date, "zenith paris");
    List<ValeurConcert> resultat = reconciliateur.reconcilierArtistes(List.of(entrant));

    assertEquals(1, resultat.size());
    assertEquals(idExact, resultat.get(0).artisteId());
    assertEquals("krash karma", resultat.get(0).artisteNorm());
  }
}
