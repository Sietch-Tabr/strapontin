/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.Normalisation;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link ReconciliationNomsSalles} — priorité exacte avant flou.
 *
 * @since 2.6.3
 * @author Kwaite
 */
class ReconciliationNomsSallesTest {

  private static ValeurConcert concert(
      final String artisteNorm, final LocalDate date, final String salleNorm) {
    return concert(artisteNorm, date, null, salleNorm, null);
  }

  private static ValeurConcert concert(
      final String artisteNorm,
      final LocalDate date,
      final LocalTime heure,
      final String salleNorm,
      final String dept) {
    return new ValeurConcert(
        "Artiste",
        artisteNorm,
        date,
        heure,
        "Salle",
        salleNorm,
        null,
        dept,
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

  // Cas réel documenté (wiki Sources-de-données, tick) : "zenith europe strasbourg" /
  // "zenith de strasbourg europe" — Jaccard tokens = 1.0 (doublon flou, réordonnancement de
  // mots). Sans ORDER BY sur la requête source, ce candidat flou peut apparaître avant un
  // candidat exact dans le result set — avant, la boucle réécrivait à tort le salleNorm
  // entrant avec l'orthographe du candidat flou trouvé en premier, ratant le candidat exact
  // (littéralement identique, donc sans changement à apporter) plus loin dans la liste.
  @Test
  void reconcilierSalles_candidatFlouAvantCandidatExact_neReecritPasAvecLeFlou() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliationNomsSalles reconciliateur = new ReconciliationNomsSalles(jdbc);

    final LocalDate date = LocalDate.of(2025, 6, 15);

    final Map<String, Object> candidatFlou = new HashMap<>();
    candidatFlou.put("artiste_norm", "coldplay");
    candidatFlou.put("date", date.toString());
    candidatFlou.put("salle_norm", "zenith europe strasbourg");

    final Map<String, Object> candidatExact = new HashMap<>();
    candidatExact.put("artiste_norm", "coldplay");
    candidatExact.put("date", date.toString());
    candidatExact.put("salle_norm", "zenith de strasbourg europe");

    // Ordre volontairement défavorable : flou avant exact.
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(candidatFlou, candidatExact));

    final ValeurConcert entrant = concert("coldplay", date, "zenith de strasbourg europe");
    final List<ValeurConcert> resultat = reconciliateur.reconcilierSalles(List.of(entrant));

    assertEquals(1, resultat.size());
    assertEquals("zenith de strasbourg europe", resultat.get(0).salleNorm());
  }

  // absorberRenommagesSalles mettait à jour salles.nom sans jamais recalculer nom_norm,
  // désynchronisant les deux colonnes avec le temps et cassant le matching Wikidata (comparaison
  // calibrée sur un nom_norm déjà normalisé — voir CorrespondanceSalle).
  @Test
  void absorberRenommagesSalles_renommageAbsorbe_recalculeNomNorm() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliationNomsSalles reconciliateur = new ReconciliationNomsSalles(jdbc);
    final UUID salleId = UUID.randomUUID();

    final ValeurConcert entrant =
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

    reconciliateur.absorberRenommagesSalles(
        List.of(entrant), Map.of("tm:venue1", salleId), Map.of());

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(jdbc).update(anyString(), paramsCaptor.capture());
    assertEquals(
        Normalisation.normaliserTexte("L'Olympia"), paramsCaptor.getValue().get("nomNorm"));
  }

  // Cas réel documenté en production (#1217) : un venueId TM déjà mappé vers une salle d'un
  // autre département ne doit pas renommer cette salle avec le nom du concert entrant — le
  // renommage court-circuiterait sinon le garde-fou géographique appliqué en aval.
  @Test
  void absorberRenommagesSalles_mappingIncoherentGeographiquement_neRenommePas() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliationNomsSalles reconciliateur = new ReconciliationNomsSalles(jdbc);
    final UUID salleQuimperId = UUID.randomUUID();

    final ValeurConcert entrant =
        new ValeurConcert(
            "Chimene Badi",
            "chimene badi",
            LocalDate.of(2027, 12, 18),
            null,
            "Parc Des Expositions Brezillet",
            "parc des expositions brezillet",
            null,
            "22",
            null,
            "ticketmaster",
            null,
            null,
            null,
            null,
            null,
            "ticketmaster:rZFSnyZAv1F",
            null,
            null,
            null);

    reconciliateur.absorberRenommagesSalles(
        List.of(entrant),
        Map.of("ticketmaster:rZFSnyZAv1F", salleQuimperId),
        Map.of(salleQuimperId, "29"));

    verify(jdbc, never()).update(anyString(), anyMap());
  }

  // Deux salles réellement distinctes (départements différents) partageant un pivot commun
  // ("kursaal", déjà un doublon flou légitime pour deux salles du même lieu) ne doivent jamais
  // fusionner entre elles au sein d'un même lot si elles ne sont pas dans le même département.
  @Test
  void reconcilierSalles_memeArtisteDateDeptDifferent_neFusionnePasIntraLot() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliationNomsSalles reconciliateur = new ReconciliationNomsSalles(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final LocalDate date = LocalDate.of(2026, 9, 1);
    final ValeurConcert concert1 = concert("artiste x", date, null, "grand kursaal", "25");
    final ValeurConcert concert2 = concert("artiste x", date, null, "kursaal besancon", "90");

    final List<ValeurConcert> resultat =
        reconciliateur.reconcilierSalles(List.of(concert1, concert2));

    assertEquals("grand kursaal", resultat.get(0).salleNorm());
    assertEquals("kursaal besancon", resultat.get(1).salleNorm());
  }

  // Même artiste/date/dept mais deux représentations distinctes (matinée/soirée) d'un même
  // titre générique dans deux salles différentes ne doivent pas non plus fusionner.
  @Test
  void reconcilierSalles_memeArtisteDateDeptHeureDifferente_neFusionnePasIntraLot() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliationNomsSalles reconciliateur = new ReconciliationNomsSalles(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final LocalDate date = LocalDate.of(2026, 9, 1);
    final ValeurConcert concert1 =
        concert("artiste x", date, LocalTime.of(15, 0), "grand kursaal", "25");
    final ValeurConcert concert2 =
        concert("artiste x", date, LocalTime.of(20, 0), "kursaal besancon", "25");

    final List<ValeurConcert> resultat =
        reconciliateur.reconcilierSalles(List.of(concert1, concert2));

    assertEquals("grand kursaal", resultat.get(0).salleNorm());
    assertEquals("kursaal besancon", resultat.get(1).salleNorm());
  }

  // Même artiste/date/dept/heure : la fusion floue doit continuer à s'appliquer normalement.
  @Test
  void reconcilierSalles_memeArtisteDateDeptHeure_fusionneIntraLotCommeAvant() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliationNomsSalles reconciliateur = new ReconciliationNomsSalles(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final LocalDate date = LocalDate.of(2026, 9, 1);
    final ValeurConcert concert1 =
        concert("artiste x", date, LocalTime.of(20, 0), "grand kursaal", "25");
    final ValeurConcert concert2 =
        concert("artiste x", date, LocalTime.of(20, 0), "kursaal besancon", "25");

    final List<ValeurConcert> resultat =
        reconciliateur.reconcilierSalles(List.of(concert1, concert2));

    assertEquals(resultat.get(0).salleNorm(), resultat.get(1).salleNorm());
  }
}
