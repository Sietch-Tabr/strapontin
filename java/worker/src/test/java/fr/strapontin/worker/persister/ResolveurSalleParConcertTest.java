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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/// Tests unitaires de {@link ResolveurSalleParConcert}.
///
/// @since 2.6.3
/// @author Kwaite
class ResolveurSalleParConcertTest {

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

  // "L'Olympia" (TM, nom_norm="l olympia") déjà en base, Eventim fetch "LOlympia" (sans
  // apostrophe, nom_norm="lolympia"). Le préfiltre par tokens seul ratait ce candidat (l'espace
  // dans "l olympia" casse le LIKE sur le token "lolympia") — la clause squash doit le retrouver.
  @Test
  void chercherSalleParTokens_apostropheOmiseParUneAutreSource_retrouveLaSalleExistante() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);
    UUID uuidExistant = UUID.randomUUID();

    Map<String, Object> candidat = new HashMap<>();
    candidat.put("id", uuidExistant);
    candidat.put("nom_norm", "l olympia");

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(candidat));

    UUID resultat = resolveur.chercherSalleParTokens("LOlympia", null);

    assertEquals(uuidExistant, resultat);
  }

  @Test
  void chercherSalleParTokens_aucunCandidat_retourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());

    assertNull(resolveur.chercherSalleParTokens("Salle inédite", null));
  }

  // Cas réel documenté (La Seine Musicale) : un nom générique de complexe multi-salles valide
  // deux salles physiquement distinctes du même complexe via le seul critère pivot ("musicale").
  // Sans score de départage, le premier candidat de la liste (ordre SQL non garanti) l'emportait
  // arbitrairement — ici volontairement placé en second pour prouver l'indépendance à l'ordre.
  @Test
  void chercherSalleParTokens_plusieursCandidatsValides_retientLeMeilleurScoreQuelQueSoitLOrdre() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);
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
        resolveur.chercherSalleParTokens("La Seine Musicale - Boulogne Billancourt", null);

    assertEquals(
        grandeSeine, resultat, "Le candidat au Jaccard de tokens le plus élevé doit gagner");
  }

  // Cas réel confirmé en production (Vincent Dedienne/Fréjus) : "Theatre le Forum Frejus"
  // (Eventim) et "Le Forum - Salle Gounod" (TM) ne partagent aucun token — la clé forte
  // artiste+date+heure+dept doit rattacher malgré l'absence totale de rapport textuel.
  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_memeArtisteDateHeureDept_retourneLaSalleExistante() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);
    UUID salleExistante = UUID.randomUUID();

    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of(salleExistante));
    // Département de la salle candidate inconnu : ne doit pas bloquer le rattachement.
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    ValeurConcert entrant =
        concert(
            "vincent dedienne",
            LocalDate.of(2027, 5, 23),
            LocalTime.of(20, 30),
            "theatre le forum frejus",
            "83");

    assertEquals(salleExistante, resolveur.chercherSalleParCleForte(entrant));
  }

  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_heureInconnue_neQuerypasEtRetourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);

    ValeurConcert entrant =
        concert("vincent dedienne", LocalDate.of(2027, 5, 23), null, "theatre x", null);

    assertNull(resolveur.chercherSalleParCleForte(entrant));
    verify(jdbc, Mockito.never()).query(anyString(), anyMap(), any(RowMapper.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_deptInconnu_neQuerypasEtRetourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);

    ValeurConcert entrant =
        concert(
            "vincent dedienne",
            LocalDate.of(2027, 5, 23),
            LocalTime.of(20, 30),
            "theatre le forum frejus",
            null);

    assertNull(resolveur.chercherSalleParCleForte(entrant));
    verify(jdbc, Mockito.never()).query(anyString(), anyMap(), any(RowMapper.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_aucunConcertCorrespondant_retourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);

    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    ValeurConcert entrant =
        concert(
            "vincent dedienne",
            LocalDate.of(2027, 5, 23),
            LocalTime.of(20, 30),
            "theatre le forum frejus",
            "83");

    assertNull(resolveur.chercherSalleParCleForte(entrant));
  }

  // Le candidat cohérent (dept correspondant) doit être retenu quel que soit l'ordre SQL.
  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_candidatCoherentEnPremier_retientLeCandidatCoherent() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);
    UUID salleIncoherente = UUID.randomUUID();
    UUID salleCoherente = UUID.randomUUID();

    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of(salleCoherente, salleIncoherente));
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                Map.of("id", salleCoherente, "dept", "22"),
                Map.of("id", salleIncoherente, "dept", "29")));

    ValeurConcert entrant =
        concert("chimene badi", LocalDate.of(2027, 12, 18), LocalTime.of(20, 0), "salle", "22");

    assertEquals(salleCoherente, resolveur.chercherSalleParCleForte(entrant));
  }

  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_candidatCoherentEnSecond_retientLeCandidatCoherent() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);
    UUID salleIncoherente = UUID.randomUUID();
    UUID salleCoherente = UUID.randomUUID();

    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of(salleIncoherente, salleCoherente));
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                Map.of("id", salleIncoherente, "dept", "29"),
                Map.of("id", salleCoherente, "dept", "22")));

    ValeurConcert entrant =
        concert("chimene badi", LocalDate.of(2027, 12, 18), LocalTime.of(20, 0), "salle", "22");

    assertEquals(salleCoherente, resolveur.chercherSalleParCleForte(entrant));
  }

  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_tousLesCandidatsIncoherents_retourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);
    UUID salleA = UUID.randomUUID();
    UUID salleB = UUID.randomUUID();

    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of(salleA, salleB));
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(Map.of("id", salleA, "dept", "29"), Map.of("id", salleB, "dept", "75")));

    ValeurConcert entrant =
        concert("chimene badi", LocalDate.of(2027, 12, 18), LocalTime.of(20, 0), "salle", "22");

    assertNull(resolveur.chercherSalleParCleForte(entrant));
  }

  // (rattrapage) : raccrocherSallesParNomNorm faisait un rattachement par égalité exacte de
  // nom_norm sans aucun filtre dept, défaisant silencieusement le filtre dept ajouté à
  // chercherSalleParTokens — deux salles homonymes au texte strictement identique (ex. "Casino -
  // Barriere" Toulouse/Lille) se faisaient quand même fusionner via ce repli en aval.
  @Test
  void raccrocherSallesParNomNorm_candidatDeptDifferent_neRattachePas() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);

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

    List<ValeurConcert> resultat = resolveur.raccrocherSallesParNomNorm(List.of(entrant));

    assertNull(resultat.get(0).salleId());
  }

  @Test
  void raccrocherSallesParNomNorm_candidatDeptCorrespondant_rattache() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc);
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

    List<ValeurConcert> resultat = resolveur.raccrocherSallesParNomNorm(List.of(entrant));

    assertEquals(uuidToulouse, resultat.get(0).salleId());
  }
}
