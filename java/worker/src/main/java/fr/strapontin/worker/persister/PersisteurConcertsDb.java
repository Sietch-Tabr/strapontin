/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de persistance SQL pur pour les concerts.
///
/// <p>Effectue l'upsert par lot en base avec la stratégie ON CONFLICT sur
/// {@code (artiste_norm, date, heure, salle_norm)} (NULLS NOT DISTINCT). Gère
/// la suppression des doublons par URL et par {@code salle_uuid} (TM+Eventim).
///
/// <p>Résout les UUIDs internes depuis les identifiants sources préfixés
/// ({@code salle_sources}, {@code artiste_sources}).
///
/// @since 2.5.7
/// @author Kwaite
@Service
public class PersisteurConcertsDb {

  private static final Logger log = LoggerFactory.getLogger(PersisteurConcertsDb.class);

  private static final String SQL_UPSERT_ENTETE =
      """
      INSERT INTO concerts
          (artiste, artiste_norm, date, heure, salle, salle_norm, ville, dept,
           url, source, classification, genre, statut,
           presale_debut, presale_nom, salle_uuid, artiste_id)
      VALUES
      """;

  private static final String SQL_UPSERT_CONFLIT =
      """
      ON CONFLICT ON CONSTRAINT uq_concerts_artiste_date_heure_salle DO UPDATE SET
          artiste        = CASE WHEN unaccent(EXCLUDED.artiste) != EXCLUDED.artiste
                                THEN EXCLUDED.artiste ELSE concerts.artiste END,
          salle          = CASE WHEN upper(EXCLUDED.salle) != EXCLUDED.salle
                                THEN EXCLUDED.salle ELSE concerts.salle END,
          heure          = EXCLUDED.heure,
          ville          = EXCLUDED.ville,
          dept           = EXCLUDED.dept,
          source         = EXCLUDED.source,
          url            = COALESCE(EXCLUDED.url, concerts.url),
          classification = COALESCE(EXCLUDED.classification, concerts.classification),
          genre          = COALESCE(EXCLUDED.genre, concerts.genre),
          statut         = CASE
                             WHEN concerts.statut IN ('cancelled', 'rescheduled', 'postponed')
                                  THEN concerts.statut
                             WHEN concerts.source = 'ticketmaster' AND EXCLUDED.source = 'eventim'
                                  THEN concerts.statut
                             ELSE EXCLUDED.statut END,
          presale_debut  = COALESCE(EXCLUDED.presale_debut, concerts.presale_debut),
          presale_nom    = COALESCE(EXCLUDED.presale_nom, concerts.presale_nom),
          salle_uuid     = COALESCE(EXCLUDED.salle_uuid, concerts.salle_uuid),
          artiste_id     = COALESCE(EXCLUDED.artiste_id, concerts.artiste_id),
          updated_at     = now()
      RETURNING (xmax = 0) AS est_insertion
      """;

  private static final String SQL_DOUBLONS_URL =
      """
      SELECT DISTINCT c1.id::text
      FROM concerts c1
      JOIN concerts c2
        ON c1.source = c2.source
       AND c1.url    = c2.url
       AND c1.date   = c2.date
       AND c1.heure IS NOT DISTINCT FROM c2.heure
       AND c1.id    != c2.id
       AND c1.updated_at < c2.updated_at
      WHERE c1.url IS NOT NULL
      """;

  private final NamedParameterJdbcTemplate jdbc;

  /// @param jdbc template JDBC injecté par Spring
  public PersisteurConcertsDb(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Résout les UUIDs internes depuis les identifiants sources préfixés.
  ///
  /// <p>Consulte {@code salle_sources} et {@code artiste_sources} par lot pour chaque
  /// source distincte. Retourne un cache {@code "source:identifiant" → UUID}.
  ///
  /// @param concerts concerts à résoudre
  /// @param type     {@code "salle"} ou {@code "artiste"}
  /// @return cache des UUIDs résolus par identifiant préfixé
  Map<String, UUID> resoudreUuidsParSource(List<ValeurConcert> concerts, String type) {
    Map<String, List<String>> parSource = new HashMap<>();
    for (ValeurConcert c : concerts) {
      String sourceId = "salle".equals(type) ? c.salleSourceId() : c.artisteSourceId();
      if (sourceId != null) {
        int sep = sourceId.indexOf(':');
        if (sep > 0) {
          String source = sourceId.substring(0, sep);
          String identifiant = sourceId.substring(sep + 1);
          parSource.computeIfAbsent(source, _ -> new ArrayList<>()).add(identifiant);
        }
      }
    }

    Map<String, UUID> cache = new HashMap<>();
    String table = "salle".equals(type) ? "salle_sources" : "artiste_sources";
    String colUuid = "salle".equals(type) ? "salle_id" : "artiste_id";

    for (var entry : parSource.entrySet()) {
      String source = entry.getKey();
      List<String> ids = entry.getValue().stream().distinct().toList();
      List<Map<String, Object>> lignes =
          jdbc.queryForList(
              "SELECT "
                  + colUuid
                  + ", identifiant_externe FROM "
                  + table
                  + " WHERE source = :source AND identifiant_externe IN (:ids)",
              Map.of("source", source, "ids", ids));
      for (var ligne : lignes) {
        String cle = source + ":" + ligne.get("identifiant_externe");
        cache.put(cle, (UUID) ligne.get(colUuid));
      }
    }
    return cache;
  }

  /// Résout les concerts dont l'URL existe déjà en base sous un {@code artiste_norm} différent.
  ///
  /// <p>Couvre le cas des pages festival Eventim : une même URL peut exposer des artistes
  /// différents selon le fetch. Sans cette résolution, deux lignes coexisteraient en base
  /// avec le même {@code (source, url, date, heure)} mais des {@code artiste_norm} différents,
  /// déclenchant une suppression cyclique dans {@link #supprimerDoublonsParUrl}.
  ///
  /// @param concerts concerts après résolution des identités sources
  /// @return liste avec les {@code artiste_norm} et {@code artiste_id} unifiés par URL
  List<ValeurConcert> resoudreParUrl(List<ValeurConcert> concerts) {
    List<ValeurConcert> avecUrl = concerts.stream().filter(c -> c.url() != null).toList();
    if (avecUrl.isEmpty()) return concerts;

    List<String> urls = avecUrl.stream().map(ValeurConcert::url).distinct().toList();
    List<Map<String, Object>> lignesExistantes =
        jdbc.queryForList(
            "SELECT artiste_norm, artiste_id, date, heure, source, url"
                + " FROM concerts WHERE url IN (:urls)",
            Map.of("urls", urls));

    if (lignesExistantes.isEmpty()) return concerts;

    record CleUrl(String source, String url, LocalDate date, LocalTime heure) {}

    Map<CleUrl, Map<String, Object>> index = new HashMap<>();
    for (Map<String, Object> l : lignesExistantes) {
      java.sql.Date sqlDate = (java.sql.Date) l.get("date");
      java.sql.Time sqlHeure = (java.sql.Time) l.get("heure");
      CleUrl cle =
          new CleUrl(
              (String) l.get("source"),
              (String) l.get("url"),
              sqlDate != null ? sqlDate.toLocalDate() : null,
              sqlHeure != null ? sqlHeure.toLocalTime() : null);
      index.putIfAbsent(cle, l);
    }

    return concerts.stream()
        .map(
            c -> {
              if (c.url() == null) return c;
              CleUrl cle = new CleUrl(c.source(), c.url(), c.date(), c.heure());
              Map<String, Object> existant = index.get(cle);
              if (existant == null) return c;
              String artisteNormExistant = (String) existant.get("artiste_norm");
              UUID artisteIdExistant = (UUID) existant.get("artiste_id");
              if (artisteNormExistant == null || artisteNormExistant.equals(c.artisteNorm()))
                return c;
              log.debug(
                  "url doublon résolu entrant={} existant={} url={}",
                  c.artisteNorm(),
                  artisteNormExistant,
                  c.url());
              UUID artisteIdFinal = c.artisteId() != null ? c.artisteId() : artisteIdExistant;
              return c.avecArtisteNormEtId(artisteNormExistant, artisteIdFinal);
            })
        .toList();
  }

  /// Insère ou met à jour un lot de concerts en une seule requête multi-lignes.
  ///
  /// <p>L'unicité de la clé de conflit dans le lot est garantie en amont par
  /// {@link PersisterConcerts#deduplicerLotReconcilie}, ce qui rend l'ON CONFLICT
  /// multi-VALUES sûr.
  ///
  /// @param valeurs concerts réconciliés et dédupliqués du lot
  /// @return décompte des insertions et mises à jour du lot
  PersisterConcerts.ResultatPersistance upsertLot(List<ValeurConcert> valeurs) {
    if (valeurs.isEmpty()) return new PersisterConcerts.ResultatPersistance(0, 0);

    StringBuilder sql = new StringBuilder(SQL_UPSERT_ENTETE);
    MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < valeurs.size(); i++) {
      if (i > 0) sql.append(",\n");
      sql.append(placeholdersLigne(i));
      ajouterParametresUpsert(params, valeurs.get(i), i);
    }
    sql.append("\n").append(SQL_UPSERT_CONFLIT);

    List<Map<String, Object>> lignes = jdbc.queryForList(sql.toString(), params);
    int inserts = 0;
    int updates = 0;
    for (Map<String, Object> ligne : lignes) {
      if (Boolean.TRUE.equals(ligne.get("est_insertion"))) inserts++;
      else updates++;
    }
    return new PersisterConcerts.ResultatPersistance(inserts, updates);
  }

  private static String placeholdersLigne(int i) {
    return "(:artiste_"
        + i
        + ", :artisteNorm_"
        + i
        + ", :date_"
        + i
        + ", :heure_"
        + i
        + ", :salle_"
        + i
        + ", :salleNorm_"
        + i
        + ", :ville_"
        + i
        + ", :dept_"
        + i
        + ", :url_"
        + i
        + ", CAST(:source_"
        + i
        + " AS source_concert)"
        + ", :classification_"
        + i
        + ", :genre_"
        + i
        + ", :statut_"
        + i
        + ", CAST(:presaleDebut_"
        + i
        + " AS timestamptz)"
        + ", :presaleNom_"
        + i
        + ", CAST(:salleUuid_"
        + i
        + " AS uuid)"
        + ", CAST(:artisteId_"
        + i
        + " AS uuid))";
  }

  private static void ajouterParametresUpsert(
      MapSqlParameterSource params, ValeurConcert v, int i) {
    params.addValue("artiste_" + i, v.artiste());
    params.addValue("artisteNorm_" + i, v.artisteNorm());
    params.addValue("date_" + i, v.date(), Types.DATE);
    params.addValue("heure_" + i, v.heure(), Types.TIME);
    params.addValue("salle_" + i, v.salle());
    params.addValue("salleNorm_" + i, v.salleNorm());
    params.addValue("ville_" + i, v.ville());
    params.addValue("dept_" + i, v.dept());
    params.addValue("url_" + i, v.url());
    params.addValue("source_" + i, v.source());
    params.addValue("classification_" + i, v.classification());
    params.addValue("genre_" + i, v.genre());
    params.addValue("statut_" + i, v.statut());
    params.addValue("presaleDebut_" + i, v.presaleDebut());
    params.addValue("presaleNom_" + i, v.presaleNom());
    params.addValue("salleUuid_" + i, v.salleUuid() != null ? v.salleUuid().toString() : null);
    params.addValue("artisteId_" + i, v.artisteId() != null ? v.artisteId().toString() : null);
  }

  /// Supprime les concerts en doublon par {@code (source, url, date, heure)}.
  ///
  /// @return nombre de concerts supprimés
  int supprimerDoublonsParUrl() {
    List<String> idsDoublons = jdbc.queryForList(SQL_DOUBLONS_URL, Map.of(), String.class);
    if (!idsDoublons.isEmpty()) {
      jdbc.update("DELETE FROM concerts WHERE id::text IN (:ids)", Map.of("ids", idsDoublons));
      log.info("doublons url supprimés count={}", idsDoublons.size());
      return idsDoublons.size();
    }
    return 0;
  }

  /// Supprime les doublons TM+Eventim non fusionnés par {@link ReconciliateurSalles}.
  ///
  /// <p>Quand TM et Eventim nomment la même salle différemment, deux lignes peuvent coexister
  /// malgré un {@code salle_uuid} identique. Cette méthode supprime la ligne Eventim en
  /// conservant la ligne TM comme référence.
  ///
  /// @return nombre de concerts supprimés
  int supprimerDoublonsParSalleUuid() {
    int nbSupprimees =
        jdbc.update(
            "DELETE FROM concerts WHERE id IN ("
                + "  SELECT c2.id"
                + "  FROM concerts c1"
                + "  JOIN concerts c2"
                + "    ON c1.artiste_id = c2.artiste_id"
                + "    AND c1.date = c2.date"
                + "    AND c1.heure IS NOT DISTINCT FROM c2.heure"
                + "    AND c1.salle_uuid = c2.salle_uuid"
                + "  WHERE c1.source = 'ticketmaster'"
                + "    AND c2.source = 'eventim'"
                + "    AND c1.artiste_id IS NOT NULL"
                + "    AND c1.salle_uuid IS NOT NULL"
                + ")",
            Map.of());
    if (nbSupprimees > 0)
      log.info("doublons TM+Eventim (salle_uuid) supprimés count={}", nbSupprimees);
    return nbSupprimees;
  }
}
