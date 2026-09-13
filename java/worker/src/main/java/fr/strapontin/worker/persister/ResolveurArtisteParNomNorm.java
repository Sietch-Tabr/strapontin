/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Résout les {@code artiste_id} manquants par lookup sur {@code artistes.nom_norm} — extrait de
 * {@link ReconciliateurArtistes} pour rester sous le seuil de complexité cyclomatique de classe
 * (50).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class ResolveurArtisteParNomNorm {

  private static final Logger LOG = LoggerFactory.getLogger(ResolveurArtisteParNomNorm.class);

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié uniquement par ReconciliateurArtistes (même package). */
  ResolveurArtisteParNomNorm(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Résout les {@code artiste_id} manquants par lookup sur {@code artistes.nom_norm}.
   *
   * <p>Couvre toutes les sources sans {@code artiste_id}, y compris Digitick qui ne fournit pas
   * d'identifiant artiste externe ({@code artisteSourceId == null}). Les mappings trouvés pour les
   * sources avec identifiant externe sont persistés dans {@code artiste_sources} (ON CONFLICT DO
   * NOTHING) pour accélérer les fetches suivants.
   *
   * @param concerts concerts après résolution par source
   * @return liste enrichie avec les {@code artiste_id} résolus par nom
   */
  /* package-private : appelé par ReconciliateurArtistes (même package). */
  List<ValeurConcert> resoudre(final List<ValeurConcert> concerts) {
    final List<String> nomsNormSansId =
        concerts.stream()
            .filter(c -> c.artisteId() == null)
            .map(ValeurConcert::artisteNorm)
            .distinct()
            .toList();
    if (nomsNormSansId.isEmpty()) {
      return concerts;
    }

    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT id, nom_norm FROM artistes WHERE nom_norm IN (:nomsNorm)",
            Map.of("nomsNorm", nomsNormSansId));

    final Map<String, UUID> parNomNorm = new HashMap<>();
    for (final Map<String, Object> ligne : lignes) {
      parNomNorm.put((String) ligne.get("nom_norm"), (UUID) ligne.get("id"));
    }
    if (parNomNorm.isEmpty()) {
      return concerts;
    }

    final List<ValeurConcert> enrichis =
        concerts.stream()
            .map(
                c -> {
                  if (c.artisteId() != null) {
                    return c;
                  }
                  final UUID artisteId = parNomNorm.get(c.artisteNorm());
                  return artisteId != null ? c.avecArtisteId(artisteId) : c;
                })
            .toList();

    persistersArtistesSources(enrichis, parNomNorm.keySet());
    return enrichis;
  }

  /**
   * Persiste dans {@code artiste_sources} les mappings nouvellement résolus par nom.
   *
   * <p>N'insère que les concerts dont {@code artisteId} vient d'être résolu (nom_norm présent dans
   * {@code nomNormsResolus}) et dont la source externe est connue ({@code artisteSourceId != null}
   * — Digitick n'en a pas).
   *
   * @param concerts concerts après résolution par nom
   * @param nomNormsResolus noms normalisés effectivement résolus par le lookup
   */
  private void persistersArtistesSources(
      final List<ValeurConcert> concerts, final Set<String> nomNormsResolus) {
    final List<SqlParameterSource> params = new ArrayList<>();
    for (final ValeurConcert c : concerts) {
      if (c.artisteId() == null
          || c.artisteSourceId() == null
          || !nomNormsResolus.contains(c.artisteNorm())) {
        continue;
      }
      final int sep = c.artisteSourceId().indexOf(':');
      if (sep <= 0) {
        continue;
      }
      final String source = c.artisteSourceId().substring(0, sep);
      final String identifiant = c.artisteSourceId().substring(sep + 1);
      params.add(
          new MapSqlParameterSource()
              .addValue("artisteId", c.artisteId().toString())
              .addValue("source", source)
              .addValue("identifiant", identifiant));
    }
    if (params.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(
        "INSERT INTO artiste_sources (artiste_id, source, identifiant_externe)"
            + " VALUES (CAST(:artisteId AS uuid), :source, :identifiant)"
            + " ON CONFLICT DO NOTHING",
        params.toArray(new SqlParameterSource[0]));
    LOG.info("artiste_sources persistés en lot count={}", params.size());
  }
}
