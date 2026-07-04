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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires de {@link ReconciliateurSalles} — priorité exacte avant flou (#794).
///
/// @since 2.6.3
/// @author Kwaite
class ReconciliateurSallesTest {

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

  // Cas réel documenté (wiki Sources-de-données, ticket #669) : "zenith europe strasbourg" /
  // "zenith de strasbourg europe" — Jaccard tokens = 1.0 (doublon flou, réordonnancement de
  // mots). Sans ORDER BY sur la requête source, ce candidat flou peut apparaître avant un
  // candidat exact dans le result set — avant #794, la boucle réécrivait à tort le salleNorm
  // entrant avec l'orthographe du candidat flou trouvé en premier, ratant le candidat exact
  // (littéralement identique, donc sans changement à apporter) plus loin dans la liste.
  @Test
  void reconcilierSalles_candidatFlouAvantCandidatExact_neReecritPasAvecLeFlou() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);

    LocalDate date = LocalDate.of(2025, 6, 15);

    Map<String, Object> candidatFlou = new HashMap<>();
    candidatFlou.put("artiste_norm", "coldplay");
    candidatFlou.put("date", date.toString());
    candidatFlou.put("salle_norm", "zenith europe strasbourg");

    Map<String, Object> candidatExact = new HashMap<>();
    candidatExact.put("artiste_norm", "coldplay");
    candidatExact.put("date", date.toString());
    candidatExact.put("salle_norm", "zenith de strasbourg europe");

    // Ordre volontairement défavorable : flou avant exact.
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(candidatFlou, candidatExact));

    ValeurConcert entrant = concert("coldplay", date, "zenith de strasbourg europe");
    List<ValeurConcert> resultat = reconciliateur.reconcilierSalles(List.of(entrant));

    assertEquals(1, resultat.size());
    assertEquals("zenith de strasbourg europe", resultat.get(0).salleNorm());
  }
}
