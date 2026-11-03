/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Tests unitaires de {@link ReconciliateurArtistes} — priorité exacte avant flou.
 *
 * @since 2.6.3
 * @author Kwaite
 */
class ReconciliateurArtistesTest {

  private static ValeurConcert concert(
      final String artisteNorm, final LocalDate date, final String salleNorm) {
    return FabriqueValeurConcert.nouveau()
        .artiste("Artiste")
        .artisteNorm(artisteNorm)
        .date(date)
        .salle("Salle")
        .salleNorm(salleNorm)
        .source("ticketmaster")
        .construire();
  }

  // Cas réel documenté (wiki Sources-de-données) : "krash karma" / "krashkarma" donnent un Jaro
  // ≈ 0.97 ≥ 0.95 (doublon flou). Sans ORDER BY sur la requête source, le candidat flou peut
  // apparaître avant le candidat exact dans le result set — avant, la boucle s'arrêtait sur
  // le premier match flou trouvé, ratant le candidat exact plus loin dans la même liste.
  @Test
  void reconcilierArtistes_candidatFlouAvantCandidatExact_prioriseLexact() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);

    final LocalDate date = LocalDate.of(2025, 6, 15);
    final UUID idFlou = UUID.randomUUID();
    final UUID idExact = UUID.randomUUID();

    final Map<String, Object> candidatFlou = new HashMap<>();
    candidatFlou.put("artiste_norm", "krashkarma");
    candidatFlou.put("artiste_id", idFlou);
    candidatFlou.put("date", date.toString());
    candidatFlou.put("salle_norm", "zenith paris");

    final Map<String, Object> candidatExact = new HashMap<>();
    candidatExact.put("artiste_norm", "krash karma");
    candidatExact.put("artiste_id", idExact);
    candidatExact.put("date", date.toString());
    candidatExact.put("salle_norm", "zenith paris");

    // Ordre volontairement défavorable : flou avant exact.
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(candidatFlou, candidatExact));

    final ValeurConcert entrant = concert("krash karma", date, "zenith paris");
    final List<ValeurConcert> resultat = reconciliateur.reconcilierArtistes(List.of(entrant));

    assertEquals(1, resultat.size());
    assertEquals(idExact, resultat.get(0).artisteId());
    assertEquals("krash karma", resultat.get(0).artisteNorm());
  }

  private static ValeurConcert concertAvecUrlEtClassification(
      final String artiste, final String url, final String classification, final String source) {
    return FabriqueValeurConcert.nouveau()
        .artiste(artiste)
        .artisteNorm(artiste.toLowerCase())
        .date(LocalDate.of(2026, 12, 12))
        .salle("Salle")
        .salleNorm("salle")
        .url(url)
        .source(source)
        .classification(classification)
        .construire();
  }

  @Test
  void corrigerParUrl_entrantIneligibleUrlExistanteNomDifferent_corrigeLaLigneExistante() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);

    final Map<String, Object> ligneExistante = new HashMap<>();
    ligneExistante.put("url", "https://tm.example/nino-arial-billet");
    ligneExistante.put("artiste_norm", "nino arbol");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneExistante));

    final ValeurConcert entrant =
        concertAvecUrlEtClassification(
            "Nino Arial", "https://tm.example/nino-arial-billet", "theatre", "ticketmaster");

    reconciliateur.corrigerParUrl(List.of(entrant));

    // Identité (evenements) et affichage de l'observation concernée (observations) sont
    // désormais deux mises à jour distinctes (#1192).
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE evenements"),
            org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(
                params ->
                    "nino arial".equals(params.getValue("nouvelArtisteNorm"))
                        && "https://tm.example/nino-arial-billet".equals(params.getValue("url"))));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE observations"),
            org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(
                params ->
                    "Nino Arial".equals(params.getValue("nouvelArtiste"))
                        && "https://tm.example/nino-arial-billet".equals(params.getValue("url"))));
  }

  @Test
  void corrigerParUrl_entrantEligibleDeezer_neCorrigePas() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);

    final ValeurConcert entrant =
        concertAvecUrlEtClassification(
            "Niño Árbol", "https://tm.example/nino-arial-billet", "music", "ticketmaster");

    reconciliateur.corrigerParUrl(List.of(entrant));

    verify(jdbc, never()).queryForList(anyString(), anyMap());
  }

  @Test
  void corrigerParUrl_aucuneLigneExistantePourCetteUrl_neCorrigePas() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final ValeurConcert entrant =
        concertAvecUrlEtClassification(
            "Nino Arial", "https://tm.example/nino-arial-billet", "theatre", "ticketmaster");

    reconciliateur.corrigerParUrl(List.of(entrant));

    verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
  }

  private static Map<String, Object> existant(final String artisteNorm) {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("url", "https://tm.example/damien-gillard-billet");
    ligne.put("artiste_norm", artisteNorm);
    ligne.put("artiste_id", null);
    ligne.put("date", "2026-12-12");
    ligne.put("salle_norm", "salle");
    return ligne;
  }

  // Ticketmaster publie « Damien Gillard », Eventim « Damien Gillard « Rude » » : deux formes
  // d'un même nom, pas une corruption à corriger par l'url.
  @Test
  void corrigerParUrl_nomExistantVarianteDuNomEntrant_neCorrigePas() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(existant("damien gillard rude")));

    reconciliateur.corrigerParUrl(
        List.of(
            concertAvecUrlEtClassification(
                "Damien Gillard",
                "https://tm.example/damien-gillard-billet",
                "theatre",
                "ticketmaster")));

    verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
  }

  // Un job qui ne reçoit que la forme courte ne ramène plus l'intitulé complet à son début.
  @Test
  void reconcilierArtistes_entrantIneligibleDebutDuNomExistant_adopteLeNomExistant() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(existant("damien gillard rude")));

    final List<ValeurConcert> resultat =
        reconciliateur.reconcilierArtistes(
            List.of(
                concertAvecUrlEtClassification(
                    "Damien Gillard",
                    "https://tm.example/damien-gillard-billet",
                    "theatre",
                    "ticketmaster")));

    assertEquals("damien gillard rude", resultat.get(0).artisteNorm());
    verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
  }

  @Test
  void reconcilierArtistes_entrantIneligibleIntituleComplet_corrigeLaLigneExistante() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(existant("damien gillard")));

    reconciliateur.reconcilierArtistes(
        List.of(
            concertAvecUrlEtClassification(
                "Damien Gillard Rude",
                "https://tm.example/damien-gillard-billet",
                "theatre",
                "ticketmaster")));

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE evenements"),
            org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(
                params -> "damien gillard rude".equals(params.getValue("nouvelArtisteNorm"))));
  }

  private static ValeurConcert concertDeLaSalle(
      final String artiste, final String salleNorm, final String classification) {
    return FabriqueValeurConcert.nouveau()
        .artiste(artiste)
        .artisteNorm(artiste.toLowerCase())
        .date(LocalDate.of(2026, 11, 4))
        .salle("Accor Arena")
        .salleNorm(salleNorm)
        .url("https://www.eventim.fr/event/sidiki-22000001")
        .source("eventim")
        .classification(classification)
        .construire();
  }

  private static Map<String, Object> existantAccorArena(final String artisteNorm) {
    final Map<String, Object> ligne = existant(artisteNorm);
    ligne.put("date", "2026-11-04");
    ligne.put("salle_norm", "accor arena");
    return ligne;
  }

  // Sidiki Diabaté : le titre Eventim « … a l accor arena » ne remplace pas le nom en base.
  @Test
  void reconcilierArtistes_titreNomPlusSalleIneligibleDeezer_garderLeNomCourt() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(existantAccorArena("sidiki diabate")));

    final List<ValeurConcert> resultat =
        reconciliateur.reconcilierArtistes(
            List.of(concertDeLaSalle("sidiki diabate a l accor arena", "accor arena", null)));

    assertEquals("sidiki diabate", resultat.get(0).artisteNorm());
    verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
  }

  // Le nom déjà dégradé en base (« … a l accor arena ») est ramené au nom court qui arrive.
  @Test
  void reconcilierArtistes_nomExistantNomPlusSalle_estRamenePourLeNomCourt() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(existantAccorArena("sidiki diabate a l accor arena")));

    reconciliateur.reconcilierArtistes(
        List.of(concertDeLaSalle("sidiki diabate", "accor arena", "music")));

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE evenements"),
            org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(
                params -> "sidiki diabate".equals(params.getValue("nouvelArtisteNorm"))));
  }
}
