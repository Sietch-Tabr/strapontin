/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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

/**
 * Tests unitaires de {@link AccueilService}.
 *
 * <p>Couvre la restriction aux matchs artiste de {@link AccueilService#marquerVus}.
 *
 * @since 2.6.3
 * @author Kwaite
 */
class AccueilServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private UserConfigService userConfigService;
  private AccueilService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    userConfigService = mock(UserConfigService.class);
    service = new AccueilService(jdbc, userConfigService);
  }

  // marquerVus effaçait à tort le badge "Nouveau" des matchs salle (jamais affichés sur
  // l'accueil, uniquement sur /spectacles) car la mise à jour ne filtrait pas par source de
  // match. Le SQL doit désormais restreindre aux concerts dont l'artiste est suivi.
  @Test
  void marquerVus_construitUneRequeteRestreinteAuxMatchsArtiste() {
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.marquerVus("user-1");

    verify(jdbc).update(sqlCaptor.capture(), anyMap());
    final String sql = sqlCaptor.getValue();
    assertTrue(sql.contains("user_artistes"), "la requête doit filtrer via user_artistes");
    assertTrue(sql.contains("vu_le IS NULL"), "seuls les matchs non vus doivent être mis à jour");
  }

  // le badge "Nouveau" par concert (est_nouveau) doit être unifié sur um.vu_le IS NULL
  // (cohérent avec SpectacleDto.estNew et l'intention "jamais vu"), pas sur first_seen (fraîcheur
  // en base) — sinon un concert ajouté il y a 10 jours et jamais vu n'affichait pas "Nouveau" sur
  // l'accueil alors qu'il l'affichait sur /spectacles.
  @Test
  @SuppressWarnings("unchecked")
  void accueil_calculEstNouveau_utiliseVuLeIsNullPasFirstSeen() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.accueil("user-1", false, null, false);

    verify(jdbc).query(sqlCaptor.capture(), anyMap(), any(RowMapper.class));
    final String sql = sqlCaptor.getValue();
    assertTrue(
        sql.contains("um.vu_le IS NULL AS est_nouveau"),
        "est_nouveau doit être calculé via um.vu_le IS NULL");
    assertFalse(
        sql.contains("c.first_seen >= (CURRENT_TIMESTAMP - INTERVAL '7 days') AS est_nouveau"),
        "l'ancien calcul basé sur first_seen ne doit plus alimenter est_nouveau");
  }

  @Test
  @SuppressWarnings("unchecked")
  void accueil_masquesVrai_filtreSurMasqueLeNotNull() {
    stubRequetesVides();
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.accueil("user-1", true, null, false);

    verify(jdbc).query(sqlCaptor.capture(), anyMap(), any(RowMapper.class));
    assertTrue(sqlCaptor.getValue().contains("um.masque_le IS NOT NULL"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void accueil_masquesFaux_filtreSurMasqueLeNull() {
    stubRequetesVides();
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.accueil("user-1", false, null, false);

    verify(jdbc).query(sqlCaptor.capture(), anyMap(), any(RowMapper.class));
    assertTrue(sqlCaptor.getValue().contains("um.masque_le IS NULL"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void accueil_indisponiblesExplicite1_nAjoutePasLaRestrictionDeStatut() {
    stubRequetesVides();
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.accueil("user-1", false, "1", false);

    verify(jdbc).query(sqlCaptor.capture(), anyMap(), any(RowMapper.class));
    assertFalse(sqlCaptor.getValue().contains("c.statut IS NULL OR c.statut IN"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void accueil_indisponiblesExplicite0_ajouteLaRestrictionDeStatut() {
    stubRequetesVides();
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.accueil("user-1", false, "0", false);

    verify(jdbc).query(sqlCaptor.capture(), anyMap(), any(RowMapper.class));
    assertTrue(sqlCaptor.getValue().contains("c.statut IS NULL OR c.statut IN"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void accueil_indisponiblesNullEtPreferenceDisponiblesSeuls_ajouteLaRestriction() {
    stubRequetesVides();
    when(userConfigService.lireFiltreDisponibles("user-1")).thenReturn(true);
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.accueil("user-1", false, null, false);

    verify(jdbc).query(sqlCaptor.capture(), anyMap(), any(RowMapper.class));
    assertTrue(sqlCaptor.getValue().contains("c.statut IS NULL OR c.statut IN"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void accueil_indisponiblesNullEtPreferenceTousLesConcerts_nAjoutePasLaRestriction() {
    stubRequetesVides();
    when(userConfigService.lireFiltreDisponibles("user-1")).thenReturn(false);
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.accueil("user-1", false, null, false);

    verify(jdbc).query(sqlCaptor.capture(), anyMap(), any(RowMapper.class));
    assertFalse(sqlCaptor.getValue().contains("c.statut IS NULL OR c.statut IN"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void accueil_nouveauVrai_filtreSurFirstSeenRecent() {
    stubRequetesVides();
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    service.accueil("user-1", false, null, true);

    verify(jdbc).query(sqlCaptor.capture(), anyMap(), any(RowMapper.class));
    assertTrue(
        sqlCaptor.getValue().contains("c.first_seen >= (CURRENT_TIMESTAMP - INTERVAL '7 days')"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void accueil_regroupementParArtiste_estTourneeVraiSiAuMoinsDeuxDates() {
    final ConcertAccueil c1 =
        new ConcertAccueil(
            "c1",
            "Radiohead",
            "radiohead",
            "a1",
            "2026-08-01",
            "20:00",
            "Zenith",
            "Paris",
            "url1",
            "onsale",
            false,
            null);
    final ConcertAccueil c2 =
        new ConcertAccueil(
            "c2",
            "Radiohead",
            "radiohead",
            "a1",
            "2026-09-01",
            "20:00",
            "Olympia",
            "Paris",
            "url2",
            "onsale",
            true,
            null);
    final ConcertAccueil c3 =
        new ConcertAccueil(
            "c3",
            "Muse",
            "muse",
            "a2",
            "2026-08-10",
            "20:00",
            "Bercy",
            "Paris",
            "url3",
            "onsale",
            false,
            null);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of(c1, c2, c3));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);

    final ReponseAccueil rep = service.accueil("user-1", false, null, false);

    final GroupeArtiste radiohead =
        rep.artistesGroupes().stream()
            .filter(g -> g.artiste().equals("Radiohead"))
            .findFirst()
            .orElseThrow();
    assertTrue(radiohead.estTournee(), "2 dates ou plus doit être considéré comme une tournée");
    assertTrue(
        radiohead.estNew(), "au moins un concert nouveau du groupe doit rendre le groupe nouveau");

    final GroupeArtiste muse =
        rep.artistesGroupes().stream()
            .filter(g -> g.artiste().equals("Muse"))
            .findFirst()
            .orElseThrow();
    assertFalse(muse.estTournee(), "1 seule date ne doit pas être une tournée");
    assertFalse(muse.estNew());
  }

  @Test
  @SuppressWarnings("unchecked")
  void accueil_nbArtistesEtNbSalles_retournesDepuisLesComptagesJdbc() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(
            org.mockito.ArgumentMatchers.contains("user_artistes"), anyMap(), eq(Integer.class)))
        .thenReturn(12);
    when(jdbc.queryForObject(
            org.mockito.ArgumentMatchers.contains("user_salles"), anyMap(), eq(Integer.class)))
        .thenReturn(3);

    final ReponseAccueil rep = service.accueil("user-1", false, null, false);

    assertThat(rep.nbArtistes()).isEqualTo(12);
    assertThat(rep.nbSalles()).isEqualTo(3);
  }

  // L'état du filtre affiché suit ce que le serveur applique, préférence comprise.
  @Test
  @SuppressWarnings("unchecked")
  void accueil_preferenceDisponiblesSeuls_renvoieExclusionEtNombreDIndisponibles() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.queryForObject(contains("AND NOT"), anyMap(), eq(Integer.class))).thenReturn(2);
    when(userConfigService.lireFiltreDisponibles("user-1")).thenReturn(true);

    final ReponseAccueil rep = service.accueil("user-1", false, null, false);

    assertFalse(rep.inclureIndisponibles());
    assertThat(rep.nbIndisponibles()).isEqualTo(2);
  }

  // Préférence « tous les concerts » : seul un « 0 » explicite permet d'exclure.
  @Test
  void accueil_exclusionExpliciteMalgrePreferenceTousLesConcerts_exclut() {
    stubRequetesVides();
    when(userConfigService.lireFiltreDisponibles("user-1")).thenReturn(false);

    assertFalse(service.accueil("user-1", false, "0", false).inclureIndisponibles());
    assertTrue(service.accueil("user-1", false, null, false).inclureIndisponibles());
  }

  @SuppressWarnings("unchecked")
  private void stubRequetesVides() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
  }
}
