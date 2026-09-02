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
/// <p>Effectue l'upsert par lot en base avec la stratégie ON CONFLICT sur {@code (artiste_norm,
/// date, heure, salle_id)} (NULLS NOT DISTINCT, migration V37). Gère la suppression des doublons
/// par URL et par {@code salle_id} (TM+Eventim), ainsi que l'alignement d'heure d'un report
/// Ticketmaster non ambigu (voir {@link #alignerHeureReportTM}).
///
/// <p>Résout les UUIDs internes depuis les identifiants sources préfixés ({@code salle_sources},
/// {@code artiste_sources}).
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
           presale_debut, presale_nom, salle_id, artiste_id)
      VALUES
      """;

  private static final String SQL_UPSERT_CONFLIT =
      """
      ON CONFLICT ON CONSTRAINT uq_concerts_artiste_date_heure_salle DO UPDATE SET
          artiste        = CASE WHEN unaccent(EXCLUDED.artiste) != EXCLUDED.artiste
                                THEN EXCLUDED.artiste ELSE concerts.artiste END,
          salle          = CASE WHEN upper(EXCLUDED.salle) != EXCLUDED.salle
                                THEN EXCLUDED.salle ELSE concerts.salle END,
          salle_norm     = CASE WHEN upper(EXCLUDED.salle) != EXCLUDED.salle
                                THEN EXCLUDED.salle_norm ELSE concerts.salle_norm END,
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
                             ELSE COALESCE(EXCLUDED.statut, concerts.statut) END,
          presale_debut  = COALESCE(EXCLUDED.presale_debut, concerts.presale_debut),
          presale_nom    = COALESCE(EXCLUDED.presale_nom, concerts.presale_nom),
          salle_id     = COALESCE(EXCLUDED.salle_id, concerts.salle_id),
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
  /// <p>Consulte {@code salle_sources} et {@code artiste_sources} par lot pour chaque source
  /// distincte. Retourne un cache {@code "source:identifiant" → UUID}.
  ///
  /// @param concerts concerts à résoudre
  /// @param type {@code "salle"} ou {@code "artiste"}
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

    boolean estSalle = "salle".equals(type);
    String sql =
        estSalle
            ? "SELECT salle_id, identifiant_externe FROM salle_sources"
                + " WHERE source = :source AND identifiant_externe IN (:ids)"
            : "SELECT artiste_id, identifiant_externe FROM artiste_sources"
                + " WHERE source = :source AND identifiant_externe IN (:ids)";
    String colUuid = estSalle ? "salle_id" : "artiste_id";

    Map<String, UUID> cache = new HashMap<>();
    for (var entry : parSource.entrySet()) {
      String source = entry.getKey();
      List<String> ids = entry.getValue().stream().distinct().toList();
      List<Map<String, Object>> lignes =
          jdbc.queryForList(sql, Map.of("source", source, "ids", ids));
      for (var ligne : lignes) {
        String cle = source + ":" + ligne.get("identifiant_externe");
        cache.put(cle, (UUID) ligne.get(colUuid));
      }
    }
    return cache;
  }

  /// Résout les concerts Eventim dont l'URL existe déjà en base sous un {@code artiste_norm}
  /// différent.
  ///
  /// <p>Couvre le cas des pages festival Eventim : une même URL peut exposer des artistes
  /// différents selon le fetch. Sans cette résolution, deux lignes coexisteraient en base avec le
  /// même {@code (source, url, date, heure)} mais des {@code artiste_norm} différents, déclenchant
  /// une suppression cyclique dans {@link #supprimerDoublonsParUrl}.
  ///
  /// <p>Restreint à Eventim (seule source concernée par le cas documenté ci-dessus) : appliqué à
  /// toutes les sources, ce mécanisme empêchait aussi toute guérison d'une corruption de {@code
  /// artiste_norm} déjà persistée une fois sa cause racine corrigée en amont — la ligne entrante,
  /// désormais correcte, se voyait reforcée vers l'ancienne valeur avant même d'atteindre l'upsert.
  /// Pour les autres sources, {@link #supprimerDoublonsParUrl} (qui conserve la ligne la plus
  /// récemment mise à jour) suffit à absorber une éventuelle ligne dupliquée transitoire, tout en
  /// laissant une résolution corrigée remplacer effectivement l'ancienne.
  ///
  /// @param concerts concerts après résolution des identités sources
  /// @return liste avec les {@code artiste_norm} et {@code artiste_id} unifiés par URL, pour
  ///   Eventim uniquement
  List<ValeurConcert> resoudreParUrl(List<ValeurConcert> concerts) {
    List<ValeurConcert> avecUrl =
        concerts.stream().filter(c -> c.url() != null && "eventim".equals(c.source())).toList();
    if (avecUrl.isEmpty()) return concerts;

    List<String> urls = avecUrl.stream().map(ValeurConcert::url).distinct().toList();
    List<Map<String, Object>> lignesExistantes =
        jdbc.queryForList(
            "SELECT artiste_norm, artiste_id, date, heure, source, url"
                + " FROM concerts WHERE url IN (:urls) AND source = 'eventim'",
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
  /// <p>L'unicité de la clé de conflit dans le lot est garantie en amont par {@link
  /// PersisterConcerts#deduplicerLotReconcilie}, ce qui rend l'ON CONFLICT multi-VALUES sûr.
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
        + ", CAST(:salleId_"
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
    params.addValue("salleId_" + i, v.salleId() != null ? v.salleId().toString() : null);
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

  /// Statuts Ticketmaster signant le remplacement d'une représentation existante (report,
  /// annulation) plutôt qu'une représentation supplémentaire.
  private static final java.util.Set<String> STATUTS_REPORT_TM =
      java.util.Set.of("rescheduled", "cancelled", "postponed");

  /// Aligne l'heure d'un concert Ticketmaster déjà en base sur celle d'un report entrant (même
  /// {@code url}, même {@code date}, heure différente, nouveau statut {@link #STATUTS_REPORT_TM}).
  ///
  /// <p>Ticketmaster réutilise le même idmanif (url stable) pour un évènement reporté à une autre
  /// heure — sans cette étape, la clé de conflit d'upsert (qui inclut l'heure) crée une nouvelle
  /// ligne à côté de l'ancienne au lieu de la mettre à jour.
  ///
  /// <p>Ne s'applique que si une seule heure distincte existe déjà pour ce couple {@code (url,
  /// date)} : certaines productions à représentations multiples (théâtre, cirque, ballet) partagent
  /// un seul idmanif Ticketmaster pour plusieurs séances le même jour (matinée/soirée) — en
  /// présence de cette ambiguïté, impossible de déterminer laquelle est concernée par le report, la
  /// ligne entrante reste alors une insertion distincte plutôt que de risquer d'altérer la mauvaise
  /// représentation.
  ///
  /// @param concerts concerts après réconciliation, avant upsert
  void alignerHeureReportTM(List<ValeurConcert> concerts) {
    List<ValeurConcert> reports =
        concerts.stream()
            .filter(
                c ->
                    c.url() != null
                        && c.heure() != null
                        && "ticketmaster".equals(c.source())
                        && c.statut() != null
                        && STATUTS_REPORT_TM.contains(c.statut()))
            .toList();
    if (reports.isEmpty()) return;

    List<String> urls = reports.stream().map(ValeurConcert::url).distinct().toList();
    List<Map<String, Object>> existants =
        jdbc.queryForList(
            "SELECT id, url, date, heure FROM concerts"
                + " WHERE source = 'ticketmaster' AND url IN (:urls)",
            Map.of("urls", urls));

    record CleUrlDate(String url, LocalDate date) {}
    Map<CleUrlDate, List<Map<String, Object>>> parCle = new HashMap<>();
    for (Map<String, Object> ligne : existants) {
      CleUrlDate cle =
          new CleUrlDate(
              (String) ligne.get("url"), ((java.sql.Date) ligne.get("date")).toLocalDate());
      parCle.computeIfAbsent(cle, _ -> new ArrayList<>()).add(ligne);
    }

    for (ValeurConcert c : reports) {
      List<Map<String, Object>> candidats = parCle.get(new CleUrlDate(c.url(), c.date()));
      if (candidats == null) continue;
      var heuresDistinctes =
          candidats.stream()
              .map(l -> ((java.sql.Time) l.get("heure")).toLocalTime())
              .collect(java.util.stream.Collectors.toSet());
      if (heuresDistinctes.size() != 1) continue;
      java.time.LocalTime heureExistante = heuresDistinctes.iterator().next();
      if (heureExistante.equals(c.heure())) continue;
      UUID idExistant = (UUID) candidats.get(0).get("id");
      jdbc.update(
          "UPDATE concerts SET heure = CAST(:heure AS time) WHERE id = CAST(:id AS uuid)",
          Map.of("heure", c.heure(), "id", idExistant.toString()));
      log.info(
          "report Ticketmaster aligné url={} date={} heure {}->{}",
          c.url(),
          c.date(),
          heureExistante,
          c.heure());
    }
  }

  /// Priorité de source (plus petit = conservé) pour {@link #supprimerDoublonsParSalleId}.
  private static final String SQL_PRIORITE_SOURCE =
      "CASE %s::text"
          + " WHEN 'ticketmaster' THEN 1"
          + " WHEN 'eventim' THEN 2"
          + " WHEN 'digitick' THEN 3"
          + " ELSE 4 END";

  /// Supprime les doublons cross-sources non fusionnés par {@link ReconciliateurSalles}.
  ///
  /// <p>Quand deux sources nomment la même salle différemment, deux lignes peuvent coexister malgré
  /// un {@code salle_id} identique. Généralisé à toutes les paires de sources — pas seulement
  /// TM/Eventim — via une priorité TM &gt; Eventim &gt; Digitick : la ligne de la source la moins
  /// prioritaire est supprimée, celle de la source la plus prioritaire est conservée comme
  /// référence.
  ///
  /// @return nombre de concerts supprimés
  int supprimerDoublonsParSalleId() {
    int nbSupprimees =
        jdbc.update(
            "DELETE FROM concerts WHERE id IN ("
                + "  SELECT c2.id"
                + "  FROM concerts c1"
                + "  JOIN concerts c2"
                + "    ON c1.artiste_id = c2.artiste_id"
                + "    AND c1.date = c2.date"
                + "    AND c1.heure IS NOT DISTINCT FROM c2.heure"
                + "    AND c1.salle_id = c2.salle_id"
                + "    AND c1.id != c2.id"
                + "  WHERE c1.artiste_id IS NOT NULL"
                + "    AND c1.salle_id IS NOT NULL"
                + "    AND "
                + SQL_PRIORITE_SOURCE.formatted("c1.source")
                + "    < "
                + SQL_PRIORITE_SOURCE.formatted("c2.source")
                + ")",
            Map.of());
    if (nbSupprimees > 0)
      log.info("doublons cross-sources (salle_id) supprimés count={}", nbSupprimees);
    return nbSupprimees;
  }
}
