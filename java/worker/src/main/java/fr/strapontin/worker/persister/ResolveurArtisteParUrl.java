/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Résout les concerts Eventim dont l'URL existe déjà en base (via une observation) sous un {@code
 * artiste_norm} différent.
 *
 * <p>Extrait de {@link PersisteurConcertsDb} pour rester sous la limite de 500 lignes par fichier.
 * Couvre le cas des pages festival Eventim : une même URL peut exposer des artistes différents
 * selon le fetch. Sans cette résolution, deux événements coexisteraient en base avec le même {@code
 * (source, url, date)} mais des {@code artiste_norm} différents.
 *
 * <p>Restreint à Eventim (seule source concernée par le cas documenté ci-dessus) : appliqué à
 * toutes les sources, ce mécanisme empêcherait toute guérison d'une corruption de {@code
 * artiste_norm} déjà persistée une fois sa cause racine corrigée en amont.
 *
 * @since 2.9.1
 * @author Kwaite
 */
@Service
class ResolveurArtisteParUrl {

  private static final Logger LOG = LoggerFactory.getLogger(ResolveurArtisteParUrl.class);

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param jdbc template JDBC injecté par Spring
   */
  ResolveurArtisteParUrl(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private record CleUrl(String source, String url, LocalDate date, LocalTime heure) {}

  /**
   * @param concerts concerts après résolution des identités sources
   * @return liste avec les {@code artiste_norm} et {@code artiste_id} unifiés par URL, pour Eventim
   *     uniquement
   */
  List<ValeurConcert> resoudreParUrl(final List<ValeurConcert> concerts) {
    final List<ValeurConcert> avecUrl =
        concerts.stream().filter(c -> c.url() != null && "eventim".equals(c.source())).toList();
    if (avecUrl.isEmpty()) {
      return concerts;
    }

    final List<String> urls = avecUrl.stream().map(ValeurConcert::url).distinct().toList();
    final List<Map<String, Object>> lignesExistantes = chargerObservationsParUrl(urls);
    if (lignesExistantes.isEmpty()) {
      return concerts;
    }

    final Map<CleUrl, Map<String, Object>> index = construireIndexUrlsExistantes(lignesExistantes);
    return concerts.stream().map(c -> reconcilierArtisteParUrl(c, index)).toList();
  }

  private List<Map<String, Object>> chargerObservationsParUrl(final List<String> urls) {
    return jdbc.queryForList(
        """
        SELECT e.artiste_norm AS artiste_norm, e.artiste_id AS artiste_id, e.date AS date,
               e.heure AS heure, o.source AS source, o.url AS url
        FROM observations o JOIN evenements e ON e.id = o.evenement_id
        WHERE o.url IN (:urls) AND o.source = 'eventim'
        """,
        Map.of("urls", urls));
  }

  private Map<CleUrl, Map<String, Object>> construireIndexUrlsExistantes(
      final List<Map<String, Object>> lignesExistantes) {
    final Map<CleUrl, Map<String, Object>> index = new HashMap<>();
    for (final Map<String, Object> l : lignesExistantes) {
      final Object dateBrute = l.get("date");
      final Object heureBrute = l.get("heure");
      final CleUrl cle =
          new CleUrl(
              (String) l.get("source"),
              (String) l.get("url"),
              dateBrute != null ? ((Date) dateBrute).toLocalDate() : null,
              heureBrute != null ? ((Time) heureBrute).toLocalTime() : null);
      index.putIfAbsent(cle, l);
    }
    return index;
  }

  private ValeurConcert reconcilierArtisteParUrl(
      final ValeurConcert c, final Map<CleUrl, Map<String, Object>> index) {
    if (c.url() == null) {
      return c;
    }
    final CleUrl cle = new CleUrl(c.source(), c.url(), c.date(), c.heure());
    final Map<String, Object> existant = index.get(cle);
    if (existant == null) {
      return c;
    }
    final String artisteNormExistant = (String) existant.get("artiste_norm");
    final UUID artisteIdExistant = (UUID) existant.get("artiste_id");
    if (artisteNormExistant == null || artisteNormExistant.equals(c.artisteNorm())) {
      return c;
    }
    LOG.debug(
        "url doublon résolu entrant={} existant={} url={}",
        c.artisteNorm(),
        artisteNormExistant,
        c.url());
    final UUID artisteIdFinal = c.artisteId() != null ? c.artisteId() : artisteIdExistant;
    return c.avecArtisteNormEtId(artisteNormExistant, artisteIdFinal);
  }
}
