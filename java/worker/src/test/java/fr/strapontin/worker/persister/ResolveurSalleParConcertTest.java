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

import fr.strapontin.sources.ClientNominatim;
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

/**
 * Tests unitaires de {@link ResolveurSalleParConcert}.
 *
 * @since 2.6.3
 * @author Kwaite
 */
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
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
    UUID uuidExistant = UUID.randomUUID();

    Map<String, Object> candidat = new HashMap<>();
    candidat.put("id", uuidExistant);
    candidat.put("nom", "L'Olympia");
    candidat.put("nom_norm", "l olympia");

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(candidat));

    UUID resultat = resolveur.chercherSalleParTokens("LOlympia", null);

    assertEquals(uuidExistant, resultat);
  }

  @Test
  void chercherSalleParTokens_aucunCandidat_retourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());

    assertNull(resolveur.chercherSalleParTokens("Salle inédite", null));
  }

  // Cas réel documenté (La Seine Musicale, #1180) : un nom générique de complexe multi-salles
  // validait autrefois deux salles physiquement distinctes du même complexe via le seul critère
  // pivot ("musicale"), départagées arbitrairement par le score Jaccard. qualificatifsDivergents
  // rejette désormais les deux candidats dès que leurs qualificatifs de sous-salle bruts diffèrent
  // — ni l'un ni l'autre halls ne doit être choisi au hasard.
  @Test
  void chercherSalleParTokens_complexeMultiSallesQualificatifsDivergents_retourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);

    Map<String, Object> candidatAuditorium = new HashMap<>();
    candidatAuditorium.put("id", UUID.randomUUID());
    candidatAuditorium.put("nom", "Seine Musicale - Auditorium P.devedjian");
    candidatAuditorium.put("nom_norm", "seine musicale auditorium p devedjian");

    Map<String, Object> candidatGrandeSeine = new HashMap<>();
    candidatGrandeSeine.put("id", UUID.randomUUID());
    candidatGrandeSeine.put("nom", "La Seine Musicale - Grande Seine");
    candidatGrandeSeine.put("nom_norm", "la seine musicale grande seine");

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(candidatAuditorium, candidatGrandeSeine));

    UUID resultat = resolveur.chercherSalleParTokens("La Seine Musicale - Petit Seine", null);

    assertNull(
        resultat, "aucun des deux halls existants ne doit être choisi pour un qualificatif tiers");
  }

  // Le score Jaccard départage toujours deux candidats dont le qualificatif correspond bel et
  // bien (variantes de casse/ponctuation de la même sous-salle) — ordre volontairement inversé
  // pour prouver l'indépendance à l'ordre SQL.
  @Test
  void chercherSalleParTokens_memeQualificatifPlusieursVariantes_retientLeMeilleurScore() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
    UUID meilleur = UUID.randomUUID();
    UUID moinsBon = UUID.randomUUID();

    Map<String, Object> candidatMoinsBon = new HashMap<>();
    candidatMoinsBon.put("id", moinsBon);
    candidatMoinsBon.put("nom", "Seine Musicale Auditorium - Grande Seine");
    candidatMoinsBon.put("nom_norm", "seine musicale auditorium grande seine");

    Map<String, Object> candidatMeilleur = new HashMap<>();
    candidatMeilleur.put("id", meilleur);
    candidatMeilleur.put("nom", "La Seine Musicale - Grande Seine");
    candidatMeilleur.put("nom_norm", "la seine musicale grande seine");

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(candidatMoinsBon, candidatMeilleur));

    UUID resultat = resolveur.chercherSalleParTokens("La Seine Musicale - Grande Seine", null);

    assertEquals(meilleur, resultat);
  }

  // Cas réel confirmé en production (Vincent Dedienne/Fréjus) : "Theatre le Forum Frejus"
  // (Eventim) et "Le Forum - Salle Gounod" (TM) ne partagent aucun token — la clé forte
  // artiste+date+heure+dept doit rattacher malgré l'absence totale de rapport textuel.
  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_memeArtisteDateHeureDept_retourneLaSalleExistante() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
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
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);

    ValeurConcert entrant =
        concert("vincent dedienne", LocalDate.of(2027, 5, 23), null, "theatre x", null);

    assertNull(resolveur.chercherSalleParCleForte(entrant));
    verify(jdbc, Mockito.never()).query(anyString(), anyMap(), any(RowMapper.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void chercherSalleParCleForte_deptInconnu_neQuerypasEtRetourneNull() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);

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
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);

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
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
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
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
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
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
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
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);

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
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
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

  // Fiche Eventim résolue au complexe avant réconciliation, puis salleNorm réécrit vers celui
  // d'une sous-salle déjà établie — le salleId doit être réaligné, pas rester celui du complexe.
  @Test
  void raccrocherSallesParNomNorm_salleIdResoluNomNormDivergent_realigneVersSousSalle() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
    UUID complexe = UUID.randomUUID();
    UUID sousSalle = UUID.randomUUID();

    Map<String, Object> nomNormComplexe = new HashMap<>();
    nomNormComplexe.put("id", complexe);
    nomNormComplexe.put("nom_norm", "la seine musicale boulogne billancourt");

    Map<String, Object> candidatSousSalle = new HashMap<>();
    candidatSousSalle.put("nom_norm", "la seine musicale grande seine");
    candidatSousSalle.put("id", sousSalle);
    candidatSousSalle.put("dept_effectif", "92");

    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(nomNormComplexe))
        .thenReturn(List.of(candidatSousSalle));

    ValeurConcert entrant =
        new ValeurConcert(
            "Lara Fabian",
            "lara fabian",
            LocalDate.of(2026, 10, 15),
            LocalTime.of(20, 0),
            "La Seine Musicale - Boulogne Billancourt",
            "la seine musicale grande seine",
            "Boulogne-Billancourt",
            "92",
            null,
            "eventim",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            complexe,
            null);

    List<ValeurConcert> resultat = resolveur.raccrocherSallesParNomNorm(List.of(entrant));

    assertEquals(sousSalle, resultat.get(0).salleId());
  }

  // Un concert résolu par clé forte sur un nom sans rapport (chercherSalleParCleForte) ne partage
  // par construction aucune correspondance exacte de nom_norm — doit rester inchangé.
  @Test
  void raccrocherSallesParNomNorm_salleIdResoluParCleForteSansHomonyme_resteInchange() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ClientNominatim clientNominatim = mock(ClientNominatim.class);
    ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
    UUID salleResolue = UUID.randomUUID();

    Map<String, Object> nomNormSalleResolue = new HashMap<>();
    nomNormSalleResolue.put("id", salleResolue);
    nomNormSalleResolue.put("nom_norm", "espace dollfus noack");

    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(nomNormSalleResolue))
        .thenReturn(List.of());

    ValeurConcert entrant =
        new ValeurConcert(
            "Artiste",
            "artiste",
            LocalDate.of(2026, 11, 28),
            null,
            "Ed&n",
            "ed n",
            null,
            "31",
            null,
            "eventim",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            salleResolue,
            null);

    List<ValeurConcert> resultat = resolveur.raccrocherSallesParNomNorm(List.of(entrant));

    assertEquals(salleResolue, resultat.get(0).salleId());
  }
}
