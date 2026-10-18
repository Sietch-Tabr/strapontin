/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.DistanceGeo;
import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.WikidataSalleSource;
import fr.strapontin.worker.LogsAdminWorker;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Contrôle a posteriori (journalisation seule) entre wikidata_id et la
 * commune Wikidata réelle — priorise concerts.ville sur salles.ville
 * (détails et incidents réels : wiki Sources-de-données).
 *
 * @since 2.7.5
 * @author Kwaite
 */
@Service
public class CoherenceWikidataSalleService {

  private static final Logger LOG = LoggerFactory.getLogger(CoherenceWikidataSalleService.class);
  private static final String SOURCE_LOG = "coherence-wikidata";
  private static final String CLE_WIKIDATA_ID = "wikidataId";

  // Position Wikidata comparable aux lieux publiés : le contrôle de position
  // (20 km) tranche ; les noms de communes signalaient à tort une agglomération
  // (Le Pin Galant à Mérignac pour des concerts annoncés à Bordeaux).
  private static final String POSITION_COMPARABLE =
      "(s.origine_position = 'wikidata' AND EXISTS (SELECT 1 FROM salle_sources ss"
          + " WHERE ss.salle_id = s.id AND ss.latitude IS NOT NULL))";

  // Au-delà, la position stockée n'est plus celle de l'élément (arrondi des
  // coordonnées Wikidata, déplacement d'un point d'entrée).
  private static final double ECART_REALIGNEMENT_METRES = 100;

  // Au-delà, une position publiée par une source ne désigne plus le même
  // lieu que l'élément Wikidata (centre-ville au lieu de la commune de la salle).
  private static final double ECART_SIGNALEMENT_METRES = 1000;
  private static final int MAX_LIEUX_DECRITS = 8;

  private final NamedParameterJdbcTemplate jdbc;
  private final WikidataSalleSource wikidata;
  private final LogsAdminWorker logsAdminWorker;
  private final SignalementMatchingService signalements;

  /**
   * @param jdbc accès JDBC
   * @param wikidata résolution de la commune Wikidata (code INSEE) pour la
   *     revalidation
   * @param logsAdminWorker journalisation des incohérences détectées
   * @param signalements signalements « À traiter » des positions de source éloignées
   */
  public CoherenceWikidataSalleService(
      final NamedParameterJdbcTemplate jdbc,
      final WikidataSalleSource wikidata,
      final LogsAdminWorker logsAdminWorker,
      final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.wikidata = wikidata;
    this.logsAdminWorker = logsAdminWorker;
    this.signalements = signalements;
  }

  /**
   * Vérifie la cohérence ville/wikidata_id de toutes les salles ayant déjà un
   * {@code wikidata_id} et une {@code ville} connus, journalise chaque
   * incohérence trouvée dans {@code logs_admin} .
   *
   * @return nombre d'incohérences détectées
   */
  public int verifier() {
    jdbc.update(
        "UPDATE salles s SET coherence_wikidata_signalee = NULL"
            + " WHERE coherence_wikidata_signalee IS NOT NULL AND "
            + POSITION_COMPARABLE,
        Map.of());
    final List<SalleAEnrichir> candidats = selectionnerCandidats();
    if (candidats.isEmpty()) {
      LOG.info("coherence:wikidata — aucun candidat");
      return 0;
    }

    final Map<UUID, EnrichissementSalle> resultats = wikidata.enrichirLot(candidats);
    final List<UUID> salleIds = candidats.stream().map(SalleAEnrichir::id).toList();
    final Map<UUID, List<String>> villesConcertsReelles = chargerVillesConcertsReelles(salleIds);
    final Map<UUID, String> signaturesDejaSignalees = chargerSignaturesDejaSignalees(salleIds);

    int incoherences = 0;
    for (final SalleAEnrichir salle : candidats) {
      final EnrichissementSalle enrichissement = resultats.get(salle.id());
      if (enrichissement != null
          && traiterSalle(
              salle,
              enrichissement,
              villesConcertsReelles.get(salle.id()),
              signaturesDejaSignalees.get(salle.id()))) {
        incoherences++;
      }
    }
    LOG.info("coherence:wikidata candidats={} incoherences={}", candidats.size(), incoherences);
    return incoherences;
  }

  // true si la salle est incohérente ; journalisée seulement si la signature change.
  private boolean traiterSalle(
      final SalleAEnrichir salle,
      final EnrichissementSalle enrichissement,
      final List<String> villesConcertsReelles,
      final String signatureConnue) {
    final String communeWikidata =
        enrichissement.ville() != null
            ? enrichissement.ville()
            : EvaluationCoherenceWikidata.COMMUNE_INCONNUE;
    final EvaluationCoherenceWikidata.Coherence coherence =
        EvaluationCoherenceWikidata.evaluer(
            salle, enrichissement, communeWikidata, villesConcertsReelles);
    if (coherence == null) {
      return false;
    }
    if (coherence == EvaluationCoherenceWikidata.Coherence.COHERENTE) {
      if (signatureConnue != null) {
        memoriserSignature(salle.id(), null);
      }
      return false;
    }
    final boolean partielle =
        coherence == EvaluationCoherenceWikidata.Coherence.INCOHERENTE_PARTIELLE;
    final String signature = communeWikidata + "|" + partielle;
    if (!signature.equals(signatureConnue)) {
      memoriserSignature(salle.id(), signature);
      loggerIncoherence(salle, communeWikidata, partielle, villesConcertsReelles);
    }
    return true;
  }

  /**
   * Revalide le type Wikidata de tous les wikidata_id stockés (garde-fou
   * introduit après coup, jamais appliqué rétroactivement) — type invalide
   * (gare/tram/commerce/administratif) remis à null directement avec les
   * coordonnées qui en venaient, redécouvert au prochain cycle.
   * ville/nom_affiche inchangés.
   * @return nombre de {@code wikidata_id} invalidés
   */
  public int revaliderTypes() {
    final List<String> wikidataIds = selectionnerTousLesWikidataId();
    if (wikidataIds.isEmpty()) {
      LOG.info("coherence:wikidata:types — aucun candidat");
      return 0;
    }

    final Set<String> invalides = wikidata.typesInvalides(wikidataIds);
    for (final String qid : invalides) {
      invaliderWikidataId(qid);
    }
    LOG.info(
        "coherence:wikidata:types candidats={} invalides={}", wikidataIds.size(), invalides.size());
    return invalides.size();
  }

  /**
   * Réaligne sur son élément actuel la position d'une salle positionnée par
   * Wikidata : une position écrite une fois (élément homonyme corrigé depuis,
   * coordonnées modifiées sur Wikidata) n'était jamais relue, et passait pour
   * un mauvais élément dans les contrôles de position.
   * @return nombre de salles réalignées
   */
  public int realignerPositions() {
    final List<SalleAEnrichir> salles = sallesPositionneesParWikidata();
    if (salles.isEmpty()) {
      return 0;
    }
    final Map<UUID, EnrichissementSalle> elements = wikidata.enrichirLot(salles);
    int realignees = 0;
    for (final SalleAEnrichir salle : salles) {
      final EnrichissementSalle element = elements.get(salle.id());
      if (element != null
          && element.latitude() != null
          && element.longitude() != null
          && realignerSiEcart(salle, element)) {
        realignees++;
      }
    }
    LOG.info("coherence:wikidata:positions candidates={} realignees={}", salles.size(), realignees);
    return realignees;
  }

  /**
   * Signale dans « À traiter », sans rien modifier, les salles dont la position
   * vient d'une source (billetterie ou géocodage) mais se trouve à plus d'un
   * kilomètre de leur élément Wikidata : l'un des deux désigne un autre lieu.
   * Le signalement se referme de lui-même quand l'écart disparaît.
   * @return nombre de salles à la position divergente
   */
  public int signalerPositionsDivergentes() {
    final List<SalleAEnrichir> salles = sallesPositionneesParUneSource();
    final Map<UUID, EnrichissementSalle> elements =
        salles.isEmpty() ? Map.of() : wikidata.enrichirLot(salles);
    // Une salle dont l'élément n'a pas pu être lu garde son signalement : l'absence de réponse
    // de Wikidata ne prouve pas que l'écart a disparu.
    final Set<UUID> encoreEnAnomalie = new HashSet<>();
    int divergentes = 0;
    for (final SalleAEnrichir salle : salles) {
      final EnrichissementSalle element = elements.get(salle.id());
      if (element == null || element.latitude() == null || element.longitude() == null) {
        encoreEnAnomalie.add(salle.id());
        continue;
      }
      final double ecart =
          DistanceGeo.metres(
              salle.latitude(), salle.longitude(), element.latitude(), element.longitude());
      if (ecart > ECART_SIGNALEMENT_METRES) {
        divergentes++;
        encoreEnAnomalie.add(salle.id());
        signalements.signaler(
            "position_source_eloignee",
            "salle",
            salle.id(),
            "moyenne",
            detailsPositionDivergente(salle, element, ecart));
      }
    }
    signalements.clore("position_source_eloignee", encoreEnAnomalie);
    LOG.info(
        "coherence:wikidata:positions-sources candidates={} divergentes={}",
        salles.size(),
        divergentes);
    return divergentes;
  }

  private List<SalleAEnrichir> sallesPositionneesParUneSource() {
    return jdbc.query(
        "SELECT id, nom, nom_norm, ville, dept, wikidata_id, latitude, longitude FROM salles"
            + " WHERE wikidata_id IS NOT NULL AND origine_position IS DISTINCT FROM 'wikidata'"
            + " AND latitude IS NOT NULL AND longitude IS NOT NULL",
        Map.of(),
        (rs, _) -> versSalleAEnrichir(rs));
  }

  // Tout ce que l'administrateur doit voir pour juger : l'élément, les deux positions, et chaque
  // lieu source avec son écart à l'élément (le lieu faux est celui qui s'en éloigne).
  private Map<String, Object> detailsPositionDivergente(
      final SalleAEnrichir salle, final EnrichissementSalle element, final double ecart) {
    final Map<String, Object> details = new LinkedHashMap<>();
    details.put(CLE_WIKIDATA_ID, salle.wikidataId());
    details.put("ecartMetres", Math.round(ecart));
    details.put("latitudeSalle", salle.latitude());
    details.put("longitudeSalle", salle.longitude());
    details.put("latitudeWikidata", element.latitude());
    details.put("longitudeWikidata", element.longitude());
    details.put("lieuxSources", decrireLieuxSources(salle.id(), element));
    return details;
  }

  private List<String> decrireLieuxSources(final UUID salleId, final EnrichissementSalle element) {
    return jdbc.query(
        """
        SELECT source, COALESCE(libelle_source, identifiant_externe) AS lieu, latitude, longitude
        FROM salle_sources
        WHERE salle_id = CAST(:id AS uuid) AND latitude IS NOT NULL AND longitude IS NOT NULL
        ORDER BY source, identifiant_externe
        LIMIT :max
        """,
        Map.of("id", salleId.toString(), "max", MAX_LIEUX_DECRITS),
        (rs, _) ->
            rs.getString("source")
                + " "
                + rs.getString("lieu")
                + " : "
                + Math.round(
                    DistanceGeo.metres(
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude"),
                        element.latitude(),
                        element.longitude()))
                + " m de l'élément");
  }

  private List<SalleAEnrichir> sallesPositionneesParWikidata() {
    return jdbc.query(
        "SELECT id, nom, nom_norm, ville, dept, wikidata_id, latitude, longitude FROM salles"
            + " WHERE wikidata_id IS NOT NULL AND origine_position = 'wikidata'"
            + " AND latitude IS NOT NULL AND longitude IS NOT NULL",
        Map.of(),
        (rs, _) -> versSalleAEnrichir(rs));
  }

  private boolean realignerSiEcart(final SalleAEnrichir salle, final EnrichissementSalle element) {
    final double ecart =
        DistanceGeo.metres(
            salle.latitude(), salle.longitude(), element.latitude(), element.longitude());
    if (ecart <= ECART_REALIGNEMENT_METRES) {
      return false;
    }
    jdbc.update(
        "UPDATE salles SET latitude = :latitude, longitude = :longitude"
            + " WHERE id = CAST(:id AS uuid)",
        Map.of(
            "latitude", element.latitude(),
            "longitude", element.longitude(),
            "id", salle.id().toString()));
    logsAdminWorker.inserer(
        "info",
        SOURCE_LOG,
        "position de salle réalignée sur son élément Wikidata salle=" + salle.nom(),
        Map.of(
            "salleId",
            salle.id().toString(),
            CLE_WIKIDATA_ID,
            salle.wikidataId(),
            "ecartMetres",
            Math.round(ecart)));
    return true;
  }

  private List<String> selectionnerTousLesWikidataId() {
    return jdbc.queryForList(
        "SELECT wikidata_id FROM salles WHERE wikidata_id IS NOT NULL", Map.of(), String.class);
  }

  private void invaliderWikidataId(final String qid) {
    jdbc.update(
        // Coordonnées issues de la même entité (P625) : fausses aussi, et elles
        // serviraient sinon à rattacher par proximité GPS des lieux sans rapport.
        "UPDATE salles SET wikidata_id = NULL, latitude = NULL, longitude = NULL,"
            + " origine_position = NULL"
            + " WHERE wikidata_id = :qid",
        Map.of("qid", qid));
    logsAdminWorker.inserer(
        "warn",
        SOURCE_LOG,
        "wikidata_id invalidé — type Wikidata désormais exclu (gare/tram/administratif/commerce)"
            + " wikidataId="
            + qid,
        Map.of(CLE_WIKIDATA_ID, qid));
  }

  /**
   * Charge, pour chaque salle candidate, la signature de la dernière
   * incohérence déjà journalisée (commune Wikidata + type de contamination),
   * absente si jamais signalée ou si la dernière incohérence signalée a depuis
   * été résolue.
   */
  private Map<UUID, String> chargerSignaturesDejaSignalees(final List<UUID> salleIds) {
    if (salleIds.isEmpty()) {
      return Map.of();
    }
    final Map<UUID, String> parSalle = new HashMap<>();
    jdbc.query(
        "SELECT id, coherence_wikidata_signalee FROM salles"
            + " WHERE id IN (:ids) AND coherence_wikidata_signalee IS NOT NULL",
        Map.of("ids", salleIds),
        (RowCallbackHandler)
            rs ->
                parSalle.put(
                    rs.getObject("id", UUID.class), rs.getString("coherence_wikidata_signalee")));
    return parSalle;
  }

  // Map.of() refuse une valeur null (réinitialisation d'une signature résolue)
  // — HashMap requis.
  private void memoriserSignature(final UUID salleId, final String signature) {
    final Map<String, Object> params = new HashMap<>();
    params.put("signature", signature);
    params.put("id", salleId.toString());
    jdbc.update(
        "UPDATE salles SET coherence_wikidata_signalee = :signature WHERE id = CAST(:id AS uuid)",
        params);
  }

  /**
   * Charge les villes réelles des concerts de chaque salle (concerts.ville,
   * jamais touchée par l'enrichissement, contrairement à salles.ville).
   * @param salleIds identifiants des salles candidates
   * @return villes réelles par salle, absente si aucun concert avec ville
   */
  private Map<UUID, List<String>> chargerVillesConcertsReelles(final List<UUID> salleIds) {
    if (salleIds.isEmpty()) {
      return Map.of();
    }
    final Map<UUID, List<String>> parSalle = new HashMap<>();
    jdbc.query(
        "SELECT DISTINCT salle_id, ville FROM concerts"
            + " WHERE salle_id IN (:ids) AND ville IS NOT NULL AND ville != ''",
        Map.of("ids", salleIds),
        (RowCallbackHandler)
            rs ->
                parSalle
                    .computeIfAbsent(rs.getObject("salle_id", UUID.class), _ -> new ArrayList<>())
                    .add(rs.getString("ville")));
    return parSalle;
  }

  /**
   * @param salle salle candidate
   * @param communeWikidata commune Wikidata (code INSEE) du wikidata_id associé
   * @param partielle {@code true} si seule une partie des concerts est
   *     incohérente (message distinct pour ne pas induire en erreur sur
   *     l'ampleur réelle)
   * @param villesConcerts villes réellement comparées, null si salles.ville
   */
  private void loggerIncoherence(
      final SalleAEnrichir salle,
      final String communeWikidata,
      final boolean partielle,
      final List<String> villesConcerts) {
    final String villesComparees =
        villesConcerts == null || villesConcerts.isEmpty()
            ? salle.ville()
            : String.join(", ", villesConcerts);
    logsAdminWorker.inserer(
        "warn",
        SOURCE_LOG,
        (partielle
                ? "wikidata_id incohérent avec une partie des concerts (contamination partielle)"
                : "wikidata_id incohérent avec la ville")
            + " salle="
            + salle.nom()
            + " villes="
            + villesComparees
            + " communeWikidata="
            + communeWikidata
            + " wikidataId="
            + salle.wikidataId(),
        Map.of(
            "salleId",
            salle.id().toString(),
            CLE_WIKIDATA_ID,
            salle.wikidataId(),
            "ville",
            salle.ville(),
            "villesComparees",
            villesComparees,
            "communeWikidata",
            communeWikidata,
            "partielle",
            partielle));
  }

  // Position des lieux publiés seulement : comparable à celle de l'élément.
  private List<SalleAEnrichir> selectionnerCandidats() {
    return jdbc.query(
        "SELECT id, nom, nom_norm, ville, dept, wikidata_id,"
            + " CASE WHEN origine_position = 'sources' THEN latitude END AS latitude,"
            + " CASE WHEN origine_position = 'sources' THEN longitude END AS longitude"
            + " FROM salles s"
            + " WHERE wikidata_id IS NOT NULL AND ville IS NOT NULL AND ville != ''"
            + " AND NOT "
            + POSITION_COMPARABLE,
        Map.of(),
        (rs, _) -> versSalleAEnrichir(rs));
  }

  private static SalleAEnrichir versSalleAEnrichir(final ResultSet rs) throws SQLException {
    return new SalleAEnrichir(
        rs.getObject("id", UUID.class),
        rs.getString("nom"),
        rs.getString("nom_norm"),
        rs.getString("ville"),
        rs.getString("dept"),
        rs.getString("wikidata_id"),
        (Double) rs.getObject("latitude"),
        (Double) rs.getObject("longitude"));
  }
}
