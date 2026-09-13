/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Corrige un artiste corrompu déjà persisté en s'ancrant sur l'identité de l'URL de l'événement
 * plutôt que sur la similarité de nom — extrait de {@link ReconciliateurArtistes} pour rester sous
 * le seuil de complexité cyclomatique de classe (50).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class CorrecteurArtisteParUrl {

  private static final Logger LOG = LoggerFactory.getLogger(CorrecteurArtisteParUrl.class);

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié uniquement par ReconciliateurArtistes (même
   * package). */
  CorrecteurArtisteParUrl(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Corrige un artiste corrompu déjà persisté en s'ancrant sur l'identité de l'URL de l'événement
   * plutôt que sur la similarité de nom.
   *
   * <p>{@code ReconciliateurArtistes.reconcilierUnArtiste} ne guérit une ligne existante que si
   * {@link fr.strapontin.core.CorrespondanceArtiste#estDoublonArtiste} relie encore l'ancien nom
   * corrompu au nouveau — un mauvais rapprochement Deezer peut produire un nom sans aucune parenté
   * textuelle avec l'original (ex. "nino arial" → "nino arbol"), rendant cette guérison
   * structurellement impossible. Deux fiches de la même URL désignent nécessairement le même
   * événement réel : cette voie corrige donc la ligne existante indépendamment de toute similarité
   * de nom, dès que l'entrant est inéligible à la résolution Deezer (nom brut fiable par
   * construction).
   *
   * @param lot concerts après résolution des identités sources
   */
  /* package-private : appelé par ReconciliateurArtistes (même package). */
  void corrigerParUrl(final List<ValeurConcert> lot) {
    final List<ValeurConcert> candidats =
        lot.stream()
            .filter(
                c ->
                    c.url() != null
                        && !EligibiliteResolutionDeezer.estEligible(c.classification(), c.source()))
            .toList();
    if (candidats.isEmpty()) {
      return;
    }

    final List<String> urls = candidats.stream().map(ValeurConcert::url).distinct().toList();
    final List<Map<String, Object>> existants =
        jdbc.queryForList(
            "SELECT DISTINCT o.url AS url, e.artiste_norm AS artiste_norm"
                + " FROM observations o JOIN evenements e ON e.id = o.evenement_id"
                + " WHERE o.url IN (:urls)",
            Map.of("urls", urls));

    final Map<String, String> artisteNormParUrl = new HashMap<>();
    for (final Map<String, Object> ligne : existants) {
      artisteNormParUrl.put((String) ligne.get("url"), (String) ligne.get("artiste_norm"));
    }

    for (final ValeurConcert c : candidats) {
      final String artisteNormExistant = artisteNormParUrl.get(c.url());
      if (artisteNormExistant == null || artisteNormExistant.equals(c.artisteNorm())) {
        continue;
      }
      corrigerArtisteParUrl(c);
    }
  }

  // Filtre par url plutôt que par (artiste_norm, date, salle_norm) comme
  // ReconciliateurArtistes.corrigerArtisteExistantEnBase — deux fiches
  // partageant la même url désignent nécessairement le même événement réel,
  // quels que soient l'ancien nom corrompu et sa date/salle éventuellement
  // elles-mêmes faussées par le même mauvais rapprochement.
  private void corrigerArtisteParUrl(final ValeurConcert concert) {
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("nouvelArtiste", concert.artiste())
            .addValue("nouvelArtisteNorm", concert.artisteNorm())
            .addValue("url", concert.url());
    // Identité (evenements.artiste_norm) et affichage de l'observation
    // concernée (observations.artiste_brut) vivent désormais sur deux tables
    // distinctes (#1192) — les deux doivent être corrigés, la seconde ne
    // portant que sur l'observation partageant cette url.
    final int nbCorrigees =
        jdbc.update(
            "UPDATE evenements SET artiste_norm = :nouvelArtisteNorm, updated_at = now()"
                + " WHERE id = (SELECT evenement_id FROM observations WHERE url = :url LIMIT 1)"
                + " AND artiste_norm != :nouvelArtisteNorm",
            params);
    jdbc.update(
        "UPDATE observations SET artiste_brut = :nouvelArtiste, updated_at = now()"
            + " WHERE url = :url",
        params);
    if (nbCorrigees > 0) {
      LOG.info(
          "artiste corrigé par url url={} nouveau={} count={}",
          concert.url(),
          concert.artisteNorm(),
          nbCorrigees);
    }
  }
}
