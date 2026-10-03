/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.WikidataSalleSource;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  // une comparaison texte-à-texte stricte ville/commune Wikidata génère du
  // bruit quasi systématique sur toute salle parisienne/lyonnaise/marseillaise
  // (arrondissement ≠ ville mère), en zone urbaine dense (quartiers/districts
  // informels sans commune propre, ex. Bercy, La Défense) ou sur une commune
  // fusionnée/intercommunalité (Wikidata garde parfois l'ancien nom d'une des
  // communes d'origine, ou référence l'intercommunalité). Cette normalisation
  // est volontairement locale à ce contrôle *a posteriori* : {@link
  // WikidataSalleSource#villeIncompatible} reste inchangé, il bloque le
  // matching *au moment de l'enrichissement* où une comparaison plus permissive
  // réduirait la couverture.
  private static final Pattern ARRONDISSEMENT =
      Pattern.compile("^(\\d{1,2})(?:e|er|eme|ieme)? +arrondissement de (paris|lyon|marseille)$");

  // Quartiers/districts informels ne correspondant à aucune commune
  // administrative, rattachés à une ou plusieurs communes possibles (ex. La
  // Défense chevauche Nanterre/Puteaux/Courbevoie).
  private static final String VILLE_PARIS = "paris";
  private static final Map<String, Set<String>> QUARTIERS_EQUIVALENTS =
      Map.of(
          "bercy", Set.of(VILLE_PARIS),
          "la defense", Set.of("nanterre", "puteaux", "courbevoie", VILLE_PARIS),
          "st germain l auxerrois", Set.of(VILLE_PARIS),
          "notre dame", Set.of(VILLE_PARIS),
          "chaussee d antin", Set.of(VILLE_PARIS),
          // Quartier administratif du 12e arrondissement de Paris où se trouve
          // l'Opéra Bastille.
          "quinze vingts", Set.of(VILLE_PARIS));

  // Longueur minimale pour le repli par containment (commune
  // fusionnée/intercommunalité, ex. « Pierre-Bénite » ⊂ « Oullins-Pierre-Bénite
  // », « Orange » ⊂ « communauté de communes du Pays d'Orange en Provence ») —
  // évite un containment trivial sur un token court.
  private static final int LONGUEUR_MIN_CONTAINMENT = 5;

  // Position Wikidata comparable aux lieux publiés : le contrôle de position
  // (20 km) tranche ; les noms de communes signalaient à tort une agglomération
  // (Le Pin Galant à Mérignac pour des concerts annoncés à Bordeaux).
  private static final String POSITION_COMPARABLE =
      "(s.origine_position = 'wikidata' AND EXISTS (SELECT 1 FROM salle_sources ss"
          + " WHERE ss.salle_id = s.id AND ss.latitude IS NOT NULL))";

  private final NamedParameterJdbcTemplate jdbc;
  private final WikidataSalleSource wikidata;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param wikidata résolution de la commune Wikidata (code INSEE) pour la
   *     revalidation
   * @param logsAdminWorker journalisation des incohérences détectées
   */
  public CoherenceWikidataSalleService(
      final NamedParameterJdbcTemplate jdbc,
      final WikidataSalleSource wikidata,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.wikidata = wikidata;
    this.logsAdminWorker = logsAdminWorker;
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
      if (enrichissement == null) {
        continue;
      }
      final String communeWikidata = enrichissement.ville();
      if (communeWikidata == null || communeWikidata.isBlank()) {
        continue;
      }
      final Coherence coherence =
          evaluerCoherence(salle, communeWikidata, villesConcertsReelles.get(salle.id()));
      final String signatureConnue = signaturesDejaSignalees.get(salle.id());
      if (coherence == Coherence.COHERENTE) {
        if (signatureConnue != null) {
          memoriserSignature(salle.id(), null);
        }
        continue;
      }
      incoherences++;
      final boolean partielle = coherence == Coherence.INCOHERENTE_PARTIELLE;
      final String signature = communeWikidata + "|" + partielle;
      if (signature.equals(signatureConnue)) {
        continue;
      }
      memoriserSignature(salle.id(), signature);
      loggerIncoherence(salle, communeWikidata, partielle, villesConcertsReelles.get(salle.id()));
    }
    LOG.info("coherence:wikidata candidats={} incoherences={}", candidats.size(), incoherences);
    return incoherences;
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
        "coherence-wikidata",
        "wikidata_id invalidé — type Wikidata désormais exclu (gare/tram/administratif/commerce)"
            + " wikidataId="
            + qid,
        Map.of("wikidataId", qid));
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
   * Résultat de {@link #evaluerCoherence} : distingue une contamination
   * *partielle* (certains concerts cohérents, d'autres non — une minorité de
   * concerts mal rattachée à une salle par ailleurs correctement enrichie)
   * d'une incohérence *totale* (aucun concert cohérent, signe classique d'un
   * mauvais rapprochement Wikidata complet).
   */
  private enum Coherence {
    COHERENTE,
    INCOHERENTE_PARTIELLE,
    INCOHERENTE_TOTALE
  }

  /**
   * Évalue la cohérence contre concerts.ville (prioritaire) ou salles.ville
   * en repli — contamination partielle journalisée distinctement du total.
   * @param salle salle candidate
   * @param communeWikidata commune Wikidata (code INSEE) du wikidata_id associé
   * @param villesConcertsReelles villes réelles des concerts, ou vide
   * @return la classification de cohérence
   */
  private static Coherence evaluerCoherence(
      final SalleAEnrichir salle,
      final String communeWikidata,
      final List<String> villesConcertsReelles) {
    if (villesConcertsReelles != null && !villesConcertsReelles.isEmpty()) {
      final long nbCoherentes =
          villesConcertsReelles.stream().filter(v -> villesCoherentes(v, communeWikidata)).count();
      if (nbCoherentes == villesConcertsReelles.size()) {
        return Coherence.COHERENTE;
      }
      return nbCoherentes > 0 ? Coherence.INCOHERENTE_PARTIELLE : Coherence.INCOHERENTE_TOTALE;
    }
    return villesCoherentes(salle.ville(), communeWikidata)
        ? Coherence.COHERENTE
        : Coherence.INCOHERENTE_TOTALE;
  }

  private static boolean villesCoherentes(final String villeSalle, final String communeWikidata) {
    final String villeSalleNorm = Normalisation.normaliserVille(villeSalle);
    final String communeNorm = Normalisation.normaliserVille(communeWikidata);
    if (villeSalleNorm.equals(communeNorm)) {
      return true;
    }
    return !Collections.disjoint(canonicaliser(villeSalleNorm), canonicaliser(communeNorm))
        || containmentCoherent(villeSalleNorm, communeNorm);
  }

  private static Set<String> canonicaliser(final String villeNorm) {
    final Matcher matcher = ARRONDISSEMENT.matcher(villeNorm);
    if (matcher.matches()) {
      return Set.of(matcher.group(2));
    }
    final Set<String> quartier = QUARTIERS_EQUIVALENTS.get(villeNorm);
    if (quartier != null) {
      return quartier;
    }
    return Set.of(villeNorm);
  }

  private static boolean containmentCoherent(final String nomA, final String nomB) {
    if (nomA.length() < LONGUEUR_MIN_CONTAINMENT || nomB.length() < LONGUEUR_MIN_CONTAINMENT) {
      return false;
    }
    return nomA.contains(nomB) || nomB.contains(nomA);
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
        "coherence-wikidata",
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
            "wikidataId",
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

  private List<SalleAEnrichir> selectionnerCandidats() {
    return jdbc.query(
        "SELECT id, nom, nom_norm, ville, dept, wikidata_id FROM salles s"
            + " WHERE wikidata_id IS NOT NULL AND ville IS NOT NULL AND ville != ''"
            + " AND NOT "
            + POSITION_COMPARABLE,
        Map.of(),
        (rs, _) ->
            new SalleAEnrichir(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getString("nom_norm"),
                rs.getString("ville"),
                rs.getString("dept"),
                rs.getString("wikidata_id"),
                null,
                null));
  }
}
