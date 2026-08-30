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
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/// Tests unitaires de {@link ReconciliateurSalles} — priorité exacte avant flou.
///
/// @since 2.6.3
/// @author Kwaite
class ReconciliateurSallesTest {

  private static ValeurConcert concert(String artisteNorm, LocalDate date, String salleNorm) {
    return concert(artisteNorm, date, null, salleNorm, null);
  }

  private static ValeurConcert concert(
      String artisteNorm, LocalDate date, LocalTime heure, String salleNorm, String dept) {
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

  // "L'Olympia" (TM, nom_norm="l olympia") déjà en base, Eventim fetch "LOlympia" (sans
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

  // Cas réel documenté (La Seine Musicale) : un nom générique de complexe multi-salles valide
  // deux salles physiquement distinctes du même complexe via le seul critère pivot ("musicale").
  // Sans score de départage, le premier candidat de la liste (ordre SQL non garanti) l'emportait
  // arbitrairement — ici volontairement placé en second pour prouver l'indépendance à l'ordre.
  @Test
  void chercherSalleParTokens_plusieursCandidatsValides_retientLeMeilleurScoreQuelQueSoitLOrdre() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);
    UUID grandeSeine = UUID.randomUUID();
    UUID auditorium = UUID.randomUUID();

    Map<String, Object> candidatAuditorium = new HashMap<>();
    candidatAuditorium.put("id", auditorium);
    candidatAuditorium.put("nom_norm", "seine musicale auditorium p devedjian");

    Map<String, Object> candidatGrandeSeine = new HashMap<>();
    candidatGrandeSeine.put("id", grandeSeine);
    candidatGrandeSeine.put("nom_norm", "la seine musicale grande seine");

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(candidatAuditorium, candidatGrandeSeine));

    UUID resultat =
        reconciliateur.chercherSalleParTokens("La Seine Musicale - Boulogne Billancourt", null);

    assertEquals(
        grandeSeine, resultat, "Le candidat au Jaccard de tokens le plus élevé doit gagner");
  }

  // absorberRenommagesSalles mettait à jour salles.nom sans jamais recalculer nom_norm,
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

  // Deux salles réellement distinctes (départements différents) partageant un pivot commun
  // ("kursaal", déjà un doublon flou légitime pour deux salles du même lieu) ne doivent jamais
  // fusionner entre elles au sein d'un même lot si elles ne sont pas dans le même département.
  @Test
  void reconcilierSalles_memeArtisteDateDeptDifferent_neFusionnePasIntraLot() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    LocalDate date = LocalDate.of(2026, 9, 1);
    ValeurConcert concert1 = concert("artiste x", date, null, "grand kursaal", "25");
    ValeurConcert concert2 = concert("artiste x", date, null, "kursaal besancon", "90");

    List<ValeurConcert> resultat = reconciliateur.reconcilierSalles(List.of(concert1, concert2));

    assertEquals("grand kursaal", resultat.get(0).salleNorm());
    assertEquals("kursaal besancon", resultat.get(1).salleNorm());
  }

  // Même artiste/date/dept mais deux représentations distinctes (matinée/soirée) d'un même
  // titre générique dans deux salles différentes ne doivent pas non plus fusionner.
  @Test
  void reconcilierSalles_memeArtisteDateDeptHeureDifferente_neFusionnePasIntraLot() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    LocalDate date = LocalDate.of(2026, 9, 1);
    ValeurConcert concert1 = concert("artiste x", date, LocalTime.of(15, 0), "grand kursaal", "25");
    ValeurConcert concert2 =
        concert("artiste x", date, LocalTime.of(20, 0), "kursaal besancon", "25");

    List<ValeurConcert> resultat = reconciliateur.reconcilierSalles(List.of(concert1, concert2));

    assertEquals("grand kursaal", resultat.get(0).salleNorm());
    assertEquals("kursaal besancon", resultat.get(1).salleNorm());
  }

  // Même artiste/date/dept/heure : la fusion floue doit continuer à s'appliquer normalement.
  @Test
  void reconcilierSalles_memeArtisteDateDeptHeure_fusionneIntraLotCommeAvant() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    LocalDate date = LocalDate.of(2026, 9, 1);
    ValeurConcert concert1 = concert("artiste x", date, LocalTime.of(20, 0), "grand kursaal", "25");
    ValeurConcert concert2 =
        concert("artiste x", date, LocalTime.of(20, 0), "kursaal besancon", "25");

    List<ValeurConcert> resultat = reconciliateur.reconcilierSalles(List.of(concert1, concert2));

    assertEquals(resultat.get(0).salleNorm(), resultat.get(1).salleNorm());
  }

  // Un concert sans salle_id (TM a omis venue.id, aucune salle suffisamment proche au moment de
  // l'insertion) doit pouvoir être résolu plus tard si une salle correspondante apparaît entre
  // temps via une autre source.
  @Test
  void retenterSallesOrphelines_salleApparueEntreTemps_resoutLeConcert() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);
    UUID concertId = UUID.randomUUID();
    UUID salleId = UUID.randomUUID();

    Map<String, Object> orphelin = new HashMap<>();
    orphelin.put("id", concertId);
    orphelin.put("salle", "Le Zenith Toulouse Metropole");
    orphelin.put("dept", "31");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(orphelin));

    Map<String, Object> candidat = new HashMap<>();
    candidat.put("id", salleId);
    candidat.put("nom_norm", "zenith toulouse metropole");
    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(candidat));

    int resolus = reconciliateur.retenterSallesOrphelines();

    assertEquals(1, resolus);
    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(
                (Map<String, Object> params) ->
                    salleId.toString().equals(params.get("salleId"))
                        && concertId.equals(params.get("id"))));
  }

  @Test
  void retenterSallesOrphelines_aucunOrphelin_neQueryPasLesCandidats() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    int resolus = reconciliateur.retenterSallesOrphelines();

    assertEquals(0, resolus);
  }

  // Cas réel confirmé en production (Vincent Dedienne/Fréjus) : "Theatre le Forum Frejus"
  // (Eventim) et "Le Forum - Salle Gounod" (TM) ne partagent aucun token — la clé forte
  // artiste+date+heure+dept doit rattacher malgré l'absence totale de rapport textuel.
  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_memeArtisteDateHeureDept_retourneLaSalleExistante() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);
    UUID salleExistante = UUID.randomUUID();

    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of(salleExistante));

    ValeurConcert entrant =
        concert(
            "vincent dedienne",
            LocalDate.of(2027, 5, 23),
            LocalTime.of(20, 30),
            "theatre le forum frejus",
            "83");

    assertEquals(salleExistante, reconciliateur.chercherSalleParCleForte(entrant));
  }

  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_heureInconnue_neQuerypasEtRetourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);

    ValeurConcert entrant = concert("vincent dedienne", LocalDate.of(2027, 5, 23), "theatre x");

    assertNull(reconciliateur.chercherSalleParCleForte(entrant));
    verify(jdbc, Mockito.never()).query(anyString(), anyMap(), any(RowMapper.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_deptInconnu_neQuerypasEtRetourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);

    ValeurConcert entrant =
        concert(
            "vincent dedienne",
            LocalDate.of(2027, 5, 23),
            LocalTime.of(20, 30),
            "theatre le forum frejus",
            null);

    assertNull(reconciliateur.chercherSalleParCleForte(entrant));
    verify(jdbc, Mockito.never()).query(anyString(), anyMap(), any(RowMapper.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_aucunConcertCorrespondant_retourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);

    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    ValeurConcert entrant =
        concert(
            "vincent dedienne",
            LocalDate.of(2027, 5, 23),
            LocalTime.of(20, 30),
            "theatre le forum frejus",
            "83");

    assertNull(reconciliateur.chercherSalleParCleForte(entrant));
  }

  // (rattrapage) : raccrocherSallesParNomNorm faisait un rattachement par égalité exacte de
  // nom_norm sans aucun filtre dept, défaisant silencieusement le filtre dept ajouté à
  // chercherSalleParTokens — deux salles homonymes au texte strictement identique (ex. "Casino -
  // Barriere" Toulouse/Lille) se faisaient quand même fusionner via ce repli en aval.
  @Test
  void raccrocherSallesParNomNorm_candidatDeptDifferent_neRattachePas() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);

    Map<String, Object> candidatLille = new HashMap<>();
    candidatLille.put("nom_norm", "casino barriere");
    candidatLille.put("id", UUID.randomUUID());
    candidatLille.put("dept_effectif", "59");

    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(candidatLille));

    ValeurConcert entrant =
        new ValeurConcert(
            "Artiste Toulouse",
            "artiste toulouse",
            LocalDate.of(2026, 11, 28),
            null,
            "Casino - Barriere",
            "casino barriere",
            "Toulouse",
            "31",
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

    List<ValeurConcert> resultat = reconciliateur.raccrocherSallesParNomNorm(List.of(entrant));

    assertNull(resultat.get(0).salleId());
  }

  @Test
  void raccrocherSallesParNomNorm_candidatDeptCorrespondant_rattache() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSalles reconciliateur = new ReconciliateurSalles(jdbc);
    UUID uuidToulouse = UUID.randomUUID();

    Map<String, Object> candidatToulouse = new HashMap<>();
    candidatToulouse.put("nom_norm", "casino barriere");
    candidatToulouse.put("id", uuidToulouse);
    candidatToulouse.put("dept_effectif", "31");

    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(candidatToulouse));

    ValeurConcert entrant =
        new ValeurConcert(
            "Artiste Toulouse",
            "artiste toulouse",
            LocalDate.of(2026, 11, 28),
            null,
            "Casino - Barriere",
            "casino barriere",
            "Toulouse",
            "31",
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

    List<ValeurConcert> resultat = reconciliateur.raccrocherSallesParNomNorm(List.of(entrant));

    assertEquals(uuidToulouse, resultat.get(0).salleId());
  }
}
