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

/**
 * Tests unitaires de {@link ReconciliateurArtistes} — priorité exacte avant flou.
 *
 * @since 2.6.3
 * @author Kwaite
 */
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

  private static ValeurConcert concertAvecUrlEtClassification(
      String artiste, String url, String classification, String source) {
    return new ValeurConcert(
        artiste,
        artiste.toLowerCase(),
        LocalDate.of(2026, 12, 12),
        null,
        "Salle",
        "salle",
        null,
        null,
        url,
        source,
        classification,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void corrigerParUrl_entrantIneligibleUrlExistanteNomDifferent_corrigeLaLigneExistante() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);

    Map<String, Object> ligneExistante = new HashMap<>();
    ligneExistante.put("url", "https://tm.example/nino-arial-billet");
    ligneExistante.put("artiste_norm", "nino arbol");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneExistante));

    ValeurConcert entrant =
        concertAvecUrlEtClassification(
            "Nino Arial", "https://tm.example/nino-arial-billet", "theatre", "ticketmaster");

    reconciliateur.corrigerParUrl(List.of(entrant));

    // Identité (evenements) et affichage de l'observation concernée (observations) sont
    // désormais deux mises à jour distinctes (#1192).
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE evenements"),
            org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.namedparam.SqlParameterSource>argThat(
                    params ->
                        "nino arial".equals(params.getValue("nouvelArtisteNorm"))
                            && "https://tm.example/nino-arial-billet"
                                .equals(params.getValue("url"))));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE observations"),
            org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.namedparam.SqlParameterSource>argThat(
                    params ->
                        "Nino Arial".equals(params.getValue("nouvelArtiste"))
                            && "https://tm.example/nino-arial-billet"
                                .equals(params.getValue("url"))));
  }

  @Test
  void corrigerParUrl_entrantEligibleDeezer_neCorrigePas() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);

    ValeurConcert entrant =
        concertAvecUrlEtClassification(
            "Niño Árbol", "https://tm.example/nino-arial-billet", "music", "ticketmaster");

    reconciliateur.corrigerParUrl(List.of(entrant));

    verify(jdbc, never()).queryForList(anyString(), anyMap());
  }

  @Test
  void corrigerParUrl_aucuneLigneExistantePourCetteUrl_neCorrigePas() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurArtistes reconciliateur = new ReconciliateurArtistes(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    ValeurConcert entrant =
        concertAvecUrlEtClassification(
            "Nino Arial", "https://tm.example/nino-arial-billet", "theatre", "ticketmaster");

    reconciliateur.corrigerParUrl(List.of(entrant));

    verify(jdbc, never())
        .update(
            anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
  }
}
