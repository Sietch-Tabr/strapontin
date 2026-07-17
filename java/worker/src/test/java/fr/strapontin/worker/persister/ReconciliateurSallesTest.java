/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.Normalisation;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

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

  // #902 : "L'Olympia" (TM, nom_norm="l olympia") déjà en base, Eventim fetch "LOlympia" (sans
  // apostrophe, nom_norm="lolympia"). Le préfiltre par tokens seul ratait ce candidat (l'espace
  // dans "l olympia" casse le LIKE sur le token "lolympia") — la clause squash doit le retrouver.
  @Test
  void chercherSalleParTokens_apostropheOmiseParUneAutreSource_retrouveLaSalleExistante() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);
    UUID uuidExistant = UUID.randomUUID();

    Map<String, Object> candidat = new HashMap<>();
    candidat.put("id", uuidExistant);
    candidat.put("nom_norm", "l olympia");

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(candidat));

    UUID resultat = reconciliateur.chercherSalleParTokens("LOlympia", null);

    assertEquals(uuidExistant, resultat);
  }

  @Test
  void chercherSalleParTokens_aucunCandidat_retourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());

    assertNull(reconciliateur.chercherSalleParTokens("Salle inédite", null));
  }

  // #969 : absorberRenommagesSalles mettait à jour salles.nom sans jamais recalculer nom_norm,
  // désynchronisant les deux colonnes avec le temps et cassant le matching Wikidata (comparaison
  // calibrée sur un nom_norm déjà normalisé — voir CorrespondanceSalle).
  @Test
  void absorberRenommagesSalles_renommageAbsorbe_recalculeNomNorm() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);
    UUID salleId = UUID.randomUUID();

    ValeurConcert entrant =
        new ValeurConcert(
            "Coldplay",
            "coldplay",
            LocalDate.of(2026, 9, 1),
            null,
            "L'Olympia",
            "l olympia",
            null,
            null,
            null,
            "ticketmaster",
            null,
            null,
            null,
            null,
            null,
            "tm:venue1",
            null,
            null,
            null);

    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    reconciliateur.absorberRenommagesSalles(List.of(entrant), Map.of("tm:venue1", salleId));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(jdbc).update(anyString(), paramsCaptor.capture());
    assertEquals(
        Normalisation.normaliserTexte("L'Olympia"), paramsCaptor.getValue().get("nomNorm"));
  }
}
