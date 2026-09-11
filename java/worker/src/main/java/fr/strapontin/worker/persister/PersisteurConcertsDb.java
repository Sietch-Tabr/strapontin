/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.Normalisation;
import java.sql.Types;
import java.time.Duration;
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

/**
 * Service de persistance SQL pur pour les concerts, sur le modèle événement canonique ({@code
 * evenements}) + observations par source ({@code observations}) — un événement réel est résolu (ou
 * créé) avant toute écriture, jamais choisi après coup par suppression d'une source perdante
 * (ancien modèle, trois mécanismes de suppression de doublon distincts).
 *
 * <p>Résout les UUIDs internes depuis les identifiants sources préfixés ({@code salle_sources},
 * {@code artiste_sources}).
 *
 * @since 2.5.7
 * @author Kwaite
 */
@Service
public class PersisteurConcertsDb {

  private static final Logger log = LoggerFactory.getLogger(PersisteurConcertsDb.class);

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param jdbc template JDBC injecté par Spring
   */
  public PersisteurConcertsDb(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Résout les UUIDs internes depuis les identifiants sources préfixés.
   *
   * <p>Consulte {@code salle_sources} et {@code artiste_sources} par lot pour chaque source
   * distincte. Retourne un cache {@code "source:identifiant" → UUID}.
   *
   * @param concerts concerts à résoudre
   * @param type {@code "salle"} ou {@code "artiste"}
   * @return cache des UUIDs résolus par identifiant préfixé
   */
  Map<String, UUID> resoudreUuidsParSource(final List<ValeurConcert> concerts, final String type) {
    final Map<String, List<String>> parSource = new HashMap<>();
    for (final ValeurConcert c : concerts) {
      final String sourceId = "salle".equals(type) ? c.salleSourceId() : c.artisteSourceId();
      if (sourceId != null) {
        final int sep = sourceId.indexOf(':');
        if (sep > 0) {
          final String source = sourceId.substring(0, sep);
          final String identifiant = sourceId.substring(sep + 1);
          parSource.computeIfAbsent(source, _ -> new ArrayList<>()).add(identifiant);
        }
      }
    }

    final boolean estSalle = "salle".equals(type);
    final String sql =
        estSalle
            ? "SELECT salle_id, identifiant_externe FROM salle_sources"
            + " WHERE source = :source AND identifiant_externe IN (:ids)"
            : "SELECT artiste_id, identifiant_externe FROM artiste_sources"
            + " WHERE source = :source AND identifiant_externe IN (:ids)";
    final String colUuid = estSalle ? "salle_id" : "artiste_id";

    final Map<String, UUID> cache = new HashMap<>();
    for (final var entry : parSource.entrySet()) {
      final String source = entry.getKey();
      final List<String> ids = entry.getValue().stream().distinct().toList();
      final List<Map<String, Object>> lignes =
          jdbc.queryForList(sql, Map.of("source", source, "ids", ids));
      for (final var ligne : lignes) {
        final String cle = source + ":" + ligne.get("identifiant_externe");
        cache.put(cle, (UUID) ligne.get(colUuid));
      }
    }
    return cache;
  }

  /**
   * Résout les concerts Eventim dont l'URL existe déjà en base (via une observation) sous un
   * {@code artiste_norm} différent.
   *
   * <p>Couvre le cas des pages festival Eventim : une même URL peut exposer des artistes
   * différents selon le fetch. Sans cette résolution, deux événements coexisteraient en base avec
   * le même {@code (source, url, date)} mais des {@code artiste_norm} différents.
   *
   * <p>Restreint à Eventim (seule source concernée par le cas documenté ci-dessus) : appliqué à
   * toutes les sources, ce mécanisme empêcherait toute guérison d'une corruption de {@code
   * artiste_norm} déjà persistée une fois sa cause racine corrigée en amont.
   *
   * @param concerts concerts après résolution des identités sources
   * @return liste avec les {@code artiste_norm} et {@code artiste_id} unifiés par URL, pour
   *   Eventim uniquement
   */
  List<ValeurConcert> resoudreParUrl(final List<ValeurConcert> concerts) {
    final List<ValeurConcert> avecUrl =
        concerts.stream().filter(c -> c.url() != null && "eventim".equals(c.source())).toList();
    if (avecUrl.isEmpty()) {
      return concerts;
    }

    final List<String> urls = avecUrl.stream().map(ValeurConcert::url).distinct().toList();
    final List<Map<String, Object>> lignesExistantes =
        jdbc.queryForList(
            """
                SELECT e.artiste_norm AS artiste_norm, e.artiste_id AS artiste_id, e.date AS date,
                       e.heure AS heure, o.source AS source, o.url AS url
                FROM observations o JOIN evenements e ON e.id = o.evenement_id
                WHERE o.url IN (:urls) AND o.source = 'eventim'
                """,
            Map.of("urls", urls));

    if (lignesExistantes.isEmpty()) {
      return concerts;
    }

    record CleUrl(String source, String url, LocalDate date, LocalTime heure) {}

    final Map<CleUrl, Map<String, Object>> index = new HashMap<>();
    for (final Map<String, Object> l : lignesExistantes) {
      final java.sql.Date sqlDate = (java.sql.Date) l.get("date");
      final java.sql.Time sqlHeure = (java.sql.Time) l.get("heure");
      final CleUrl cle =
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
              log.debug(
                  "url doublon résolu entrant={} existant={} url={}",
                  c.artisteNorm(),
                  artisteNormExistant,
                  c.url());
              final UUID artisteIdFinal = c.artisteId() != null ? c.artisteId() : artisteIdExistant;
              return c.avecArtisteNormEtId(artisteNormExistant, artisteIdFinal);
            })
        .toList();
  }

  /**
   * Statuts Ticketmaster signant le remplacement d'une représentation existante (report,
   * annulation) plutôt qu'une représentation supplémentaire.
   */
  private static final java.util.Set<String> STATUTS_REPORT_TM =
      java.util.Set.of("rescheduled", "cancelled", "postponed");

  /**
   * Aligne l'heure d'un événement Ticketmaster déjà en base sur celle d'un report entrant (une de
   * ses observations partage la même {@code url}, même {@code date}, heure différente, nouveau
   * statut {@link #STATUTS_REPORT_TM}).
   *
   * <p>Ticketmaster réutilise le même idmanif (url stable) pour un évènement reporté à une autre
   * heure — sans cette étape, la résolution par clé (qui inclut l'heure) créerait un nouvel
   * événement à côté de l'ancien au lieu de le mettre à jour.
   *
   * <p>Ne s'applique que si une seule heure distincte existe déjà pour ce couple {@code (url,
   * date)} : certaines productions à représentations multiples (théâtre, cirque, ballet) partagent
   * un seul idmanif Ticketmaster pour plusieurs séances le même jour (matinée/soirée) — en
   * présence de cette ambiguïté, impossible de déterminer laquelle est concernée par le report, un
   * nouvel événement est alors créé plutôt que de risquer d'altérer la mauvaise représentation.
   *
   * @param concerts concerts après réconciliation, avant upsert
   */
  void alignerHeureReportTM(final List<ValeurConcert> concerts) {
    final List<ValeurConcert> reports =
        concerts.stream()
            .filter(
                c ->
                    c.url() != null
                        && c.heure() != null
                        && "ticketmaster".equals(c.source())
                        && c.statut() != null
                        && STATUTS_REPORT_TM.contains(c.statut()))
            .toList();
    if (reports.isEmpty()) {
      return;
    }

    final List<String> urls = reports.stream().map(ValeurConcert::url).distinct().toList();
    final List<Map<String, Object>> existants =
        jdbc.queryForList(
            """
                SELECT e.id AS id, o.url AS url, e.date AS date, e.heure AS heure
                FROM observations o JOIN evenements e ON e.id = o.evenement_id
                WHERE o.source = 'ticketmaster' AND o.url IN (:urls)
                """,
            Map.of("urls", urls));

    record CleUrlDate(String url, LocalDate date) {}
    final Map<CleUrlDate, List<Map<String, Object>>> parCle = new HashMap<>();
    for (final Map<String, Object> ligne : existants) {
      final CleUrlDate cle =
          new CleUrlDate(
              (String) ligne.get("url"), ((java.sql.Date) ligne.get("date")).toLocalDate());
      parCle.computeIfAbsent(cle, _ -> new ArrayList<>()).add(ligne);
    }

    for (final ValeurConcert c : reports) {
      final List<Map<String, Object>> candidats = parCle.get(new CleUrlDate(c.url(), c.date()));
      if (candidats == null) {
        continue;
      }
      final var heuresDistinctes =
          candidats.stream()
              .map(l -> ((java.sql.Time) l.get("heure")).toLocalTime())
              .collect(java.util.stream.Collectors.toSet());
      if (heuresDistinctes.size() != 1) {
        continue;
      }
      final LocalTime heureExistante = heuresDistinctes.iterator().next();
      if (heureExistante.equals(c.heure())) {
        continue;
      }
      final UUID idExistant = (UUID) candidats.get(0).get("id");
      jdbc.update(
          "UPDATE evenements SET heure = CAST(:heure AS time) WHERE id = CAST(:id AS uuid)",
          Map.of("heure", c.heure(), "id", idExistant.toString()));
      log.info(
          "report Ticketmaster aligné url={} date={} heure {}->{}",
          c.url(),
          c.date(),
          heureExistante,
          c.heure());
    }
  }

  /**
   * Motif d'URL Ticketmaster signant un événement "package"/VIP — jamais accessible à un achat de
   * billetterie standard, ne doit jamais empêcher le rapprochement avec la fiche normale du même
   * événement réel.
   */
  private static boolean estPackage(final String url) {
    return url != null && url.matches("(?i).*\\Q/pack/\\E.*");
  }

  // Tolérance sur l'écart d'heure entre deux observations d'un même événement réel (fiches TM de
  // types différents pour le même évènement) — très en dessous de tout écart matinée/soirée réel.
  private static final int TOLERANCE_HEURE_MINUTES = 3;

  private static boolean heuresCompatibles(
      final LocalTime existante, final LocalTime entrante, final boolean packageExistant, final boolean packageEntrant) {
    if (packageExistant || packageEntrant) {
      return true;
    }
    if (existante == null || entrante == null) {
      return true;
    }
    final long diffMinutes = Math.abs(Duration.between(existante, entrante).toMinutes());
    return diffMinutes <= TOLERANCE_HEURE_MINUTES;
  }

  private record ResolutionEvenement(UUID id, boolean nouveau) {}

  /**
   * Résout l'événement canonique correspondant à un concert entrant, ou en crée un nouveau — point
   * unique de dédoublonnage (#1192), remplaçant les trois mécanismes de suppression a posteriori
   * de l'ancien modèle.
   *
   * <p>Trois niveaux : correspondance exacte sur la clé {@code (artiste_norm, date, heure,
   * salle_id)} (NULLS NOT DISTINCT) ; à défaut, repli flou parmi les événements de la même salle à
   * la même date, avec tolérance d'heure package/normal ({@link #heuresCompatibles}) et
   * comparaison floue de l'artiste ({@link CorrespondanceArtiste#estDoublonArtiste}) quand aucun
   * {@code artiste_id} n'est résolu des deux côtés — mêmes critères que les anciens {@code
   * supprimerDoublonsParSalleId}/{@code supprimerDoublonsSansArtisteId}, appliqués avant
   * l'écriture plutôt qu'en nettoyage après coup ; sinon, création.
   */
  private ResolutionEvenement resoudreOuCreerEvenement(final ValeurConcert v) {
    final MapSqlParameterSource paramsExact =
        new MapSqlParameterSource()
            .addValue("artisteNorm", v.artisteNorm())
            .addValue("date", v.date(), Types.DATE)
            .addValue("heure", v.heure(), Types.TIME)
            .addValue(
                "salleId", v.salleId() != null ? v.salleId().toString() : null, Types.VARCHAR);
    final List<UUID> exact =
        jdbc.query(
            "SELECT id FROM evenements WHERE artiste_norm = :artisteNorm AND date = :date"
                + " AND heure IS NOT DISTINCT FROM :heure"
                + " AND salle_id IS NOT DISTINCT FROM CAST(:salleId AS uuid)",
            paramsExact,
            (rs, _) -> rs.getObject("id", UUID.class));
    if (!exact.isEmpty()) {
      return new ResolutionEvenement(exact.get(0), false);
    }

    if (v.salleId() != null) {
      final List<Map<String, Object>> candidats =
          jdbc.queryForList(
              """
                  SELECT e.id AS id, e.artiste_id AS artiste_id, e.artiste_norm AS artiste_norm,
                         e.heure AS heure,
                         EXISTS(SELECT 1 FROM observations o WHERE o.evenement_id = e.id
                                AND o.url ~* '/pack/') AS a_observation_package
                  FROM evenements e WHERE e.salle_id = CAST(:salleId AS uuid) AND e.date = :date
                  """,
              Map.of("salleId", v.salleId().toString(), "date", v.date()));
      final boolean entrantPackage = estPackage(v.url());
      for (final var c : candidats) {
        final UUID candArtisteId = (UUID) c.get("artiste_id");
        final String candArtisteNorm = (String) c.get("artiste_norm");
        final boolean artisteCorrespond =
            v.artisteId() != null
                ? v.artisteId().equals(candArtisteId)
                : candArtisteId == null
                && CorrespondanceArtiste.estDoublonArtiste(candArtisteNorm, v.artisteNorm());
        if (!artisteCorrespond) {
          continue;
        }
        final java.sql.Time heureSql = (java.sql.Time) c.get("heure");
        final LocalTime heureExistante = heureSql != null ? heureSql.toLocalTime() : null;
        final boolean candidatPackage = Boolean.TRUE.equals(c.get("a_observation_package"));
        if (!heuresCompatibles(heureExistante, v.heure(), candidatPackage, entrantPackage)) {
          continue;
        }
        return new ResolutionEvenement((UUID) c.get("id"), false);
      }
    }

    final UUID nouveauId =
        jdbc.queryForObject(
            """
                INSERT INTO evenements
                    (artiste_norm, date, heure, salle_id, salle_norm, ville, dept, classification,
                     genre, statut, presale_debut, presale_nom, artiste_id)
                VALUES (:artisteNorm, :date, :heure, CAST(:salleId AS uuid), :salleNorm, :ville, :dept,
                        :classification, :genre, :statut, CAST(:presaleDebut AS timestamptz),
                        :presaleNom, CAST(:artisteId AS uuid))
                RETURNING id
                """,
            paramsCreation(v),
            UUID.class);
    return new ResolutionEvenement(nouveauId, true);
  }

  private static MapSqlParameterSource paramsCreation(final ValeurConcert v) {
    return new MapSqlParameterSource()
        .addValue("artisteNorm", v.artisteNorm())
        .addValue("date", v.date(), Types.DATE)
        .addValue("heure", v.heure(), Types.TIME)
        .addValue("salleId", v.salleId() != null ? v.salleId().toString() : null)
        .addValue("salle", v.salle())
        .addValue("salleNorm", v.salleNorm())
        .addValue("ville", v.ville())
        .addValue("dept", v.dept())
        .addValue("classification", v.classification())
        .addValue("genre", v.genre())
        .addValue("statut", v.statut())
        .addValue("presaleDebut", v.presaleDebut())
        .addValue("presaleNom", v.presaleNom())
        .addValue("artisteId", v.artisteId() != null ? v.artisteId().toString() : null);
  }

  /**
   * Met à jour les champs dénormalisés d'un événement déjà résolu — jamais {@code artiste_norm} ni
   * {@code heure} (identité stable une fois l'événement résolu) — priorité à la valeur entrante,
   * repli sur la valeur déjà connue si absente, même politique que l'ancien {@code
   * SQL_UPSERT_CONFLIT}. {@code statut} ne recule jamais depuis un état terminal ({@code
   * cancelled}/{@code rescheduled}/{@code postponed}). {@code salle_norm} est resynchronisé sur la
   * même observation que la vue {@code concerts} retiendrait comme référence ({@link
   * #meilleurSalleBrut}) — utile quand {@code salle_id} n'est pas encore résolu (l'identité repose
   * alors sur {@code artiste_norm}+date+heure seuls, {@code salle_norm} restant purement
   * informatif) ; appelé après {@link #upsertObservation} pour refléter l'observation qui vient
   * d'être upsertée.
   */
  private void mettreAJourChampsEvenement(final UUID evenementId, final ValeurConcert v) {
    final String salleBrutReference = meilleurSalleBrut(evenementId);
    final String salleNormReference =
        salleBrutReference != null ? Normalisation.normaliserTexte(salleBrutReference) : null;
    jdbc.update(
        """
        UPDATE evenements SET
            salle_norm     = COALESCE(:salleNormReference, salle_norm),
            ville          = COALESCE(:ville, ville),
            dept           = COALESCE(:dept, dept),
            classification = COALESCE(:classification, classification),
            genre          = COALESCE(:genre, genre),
            statut         = CASE
                               WHEN statut IN ('cancelled', 'rescheduled', 'postponed') THEN statut
                               ELSE COALESCE(:statut, statut) END,
            presale_debut  = COALESCE(CAST(:presaleDebut AS timestamptz), presale_debut),
            presale_nom    = COALESCE(:presaleNom, presale_nom),
            salle_id       = COALESCE(CAST(:salleId AS uuid), salle_id),
            artiste_id     = COALESCE(CAST(:artisteId AS uuid), artiste_id),
            updated_at     = now()
        WHERE id = CAST(:id AS uuid)
        """,
        paramsCreation(v)
            .addValue("id", evenementId.toString())
            .addValue("salleNormReference", salleNormReference));
  }

  private void upsertObservation(final UUID evenementId, final ValeurConcert v) {
    jdbc.update(
        """
        INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)
        VALUES (CAST(:evenementId AS uuid), CAST(:source AS source_concert), :url, :artiste, :salle)
        ON CONFLICT ON CONSTRAINT uq_observations DO UPDATE SET
            artiste_brut = CASE WHEN unaccent(EXCLUDED.artiste_brut) != EXCLUDED.artiste_brut
                                THEN EXCLUDED.artiste_brut ELSE observations.artiste_brut END,
            salle_brut   = CASE WHEN upper(EXCLUDED.salle_brut) != EXCLUDED.salle_brut
                                THEN EXCLUDED.salle_brut ELSE observations.salle_brut END,
            updated_at   = now()
        """,
        new MapSqlParameterSource()
            .addValue("evenementId", evenementId.toString())
            .addValue("source", v.source())
            .addValue("url", v.url())
            .addValue("artiste", v.artiste())
            .addValue("salle", v.salle()));
  }

  /**
   * Retrouve, parmi les observations déjà attachées à un événement, le {@code salle_brut} de celle
   * qui servirait de référence dans la vue {@code concerts} — même priorité (package puis TM &gt;
   * Eventim &gt; Digitick) que la vue elle-même, pour garder {@code evenements.salle_norm}
   * cohérent avec ce que {@code concerts.salle} affichera.
   */
  private String meilleurSalleBrut(final UUID evenementId) {
    final List<String> resultat =
        jdbc.query(
            """
                SELECT salle_brut FROM observations WHERE evenement_id = CAST(:id AS uuid)
                ORDER BY (CASE WHEN url ~* '/pack/' THEN 10 ELSE 0 END
                          + CASE source::text
                              WHEN 'ticketmaster' THEN 1 WHEN 'eventim' THEN 2 WHEN 'digitick' THEN 3
                              ELSE 4 END)
                LIMIT 1
                """,
            Map.of("id", evenementId.toString()),
            (rs, _) -> rs.getString("salle_brut"));
    return resultat.isEmpty() ? null : resultat.get(0);
  }

  /**
   * Résout ou crée l'événement canonique de chaque concert du lot, upsert l'observation de sa
   * source puis met à jour les champs dénormalisés de l'événement — un aller-retour par concert
   * plutôt qu'un unique {@code INSERT} multi-lignes (volume trivial en production, ~4000
   * événements), en échange d'une résolution correcte avant écriture plutôt qu'un nettoyage après
   * coup.
   *
   * @param valeurs concerts réconciliés et dédupliqués du lot
   * @return décompte des événements créés et mis à jour du lot
   */
  PersisterConcerts.ResultatPersistance upsertLot(final List<ValeurConcert> valeurs) {
    if (valeurs.isEmpty()) {
      return new PersisterConcerts.ResultatPersistance(0, 0);
    }

    int inserts = 0;
    int updates = 0;
    for (final ValeurConcert v : valeurs) {
      final ResolutionEvenement resolution = resoudreOuCreerEvenement(v);
      upsertObservation(resolution.id(), v);
      if (!resolution.nouveau()) {
        mettreAJourChampsEvenement(resolution.id(), v);
        updates++;
      } else {
        inserts++;
      }
    }
    return new PersisterConcerts.ResultatPersistance(inserts, updates);
  }
}
