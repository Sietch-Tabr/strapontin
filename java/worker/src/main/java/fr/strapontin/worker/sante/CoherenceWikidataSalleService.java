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
 * Contrôle de cohérence *a posteriori* entre {@code salles.wikidata_id} (déjà connu) et {@code salles.ville}
 * (découvert en marge de l'investigation) : 7 salles sur 12 avaient un {@code wikidata_id} pointant vers
 * une entité totalement étrangère — Q-ID matché sur un homonyme d'une autre ville (ex. « Theatre
 * Municipal » de Béthune rattaché au musée des Beaux-Arts de Nancy) — jamais détecté faute de
 * contrôle après coup, uniquement trouvé par un audit manuel ponctuel.
 *
 * {@link WikidataSalleSource#enrichirLot} rejette déjà ce type d'incohérence *au moment du
 * matching* ({@code villeIncompatible}) mais seulement quand {@code salle.ville} et la commune du candidat
 * sont toutes les deux connues — une ville locale inconnue ou fausse au moment de l'enrichissement
 * (le cas des 7 salles ci-dessus) ne bloque rien, choix assumé pour ne pas réduire la couverture
 * d'enrichissement des salles légitimement sans ville. Ce service ne change rien à ce comportement
 * : il journalise seulement les incohérences déjà présentes en base pour un examen manuel
 * ultérieur, en réutilisant la commune déjà récupérée par le mode batch de {@link
 * WikidataSalleSource} (aucune requête SPARQL supplémentaire par rapport à un enrichissement
 * classique à {@code wikidata_id} connu).
 *
 * <p>Angle mort corrigé : la comparaison initiale contre {@code salles.ville} seule est aveugle par
 * construction quand le mauvais rapprochement a corrompu {@code ville} en même temps que {@code wikidata_id}
 * (la mauvaise entité écrase les deux au même moment) — les deux valeurs corrompues sont alors
 * toujours cohérentes entre elles. {@code concerts.ville}, dérivée du code postal brut de la source et
 * jamais touchée par l'enrichissement, sert désormais de référence prioritaire quand elle est
 * disponible ; {@code salles.ville} reste le repli pour une salle sans concert avec ville connue (ex.
 * tous ses concerts passés déjà purgés).
 *
 * @since 2.7.5
 * @author Kwaite
 */
@Service
public class CoherenceWikidataSalleService {

  private static final Logger log = LoggerFactory.getLogger(CoherenceWikidataSalleService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final WikidataSalleSource wikidata;
  private final LogsAdminWorker logsAdminWorker;

  public CoherenceWikidataSalleService(
      final NamedParameterJdbcTemplate jdbc,
      final WikidataSalleSource wikidata,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.wikidata = wikidata;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Vérifie la cohérence ville/wikidata_id de toutes les salles ayant déjà un {@code wikidata_id}
   * et une {@code ville} connus, journalise chaque incohérence trouvée dans {@code logs_admin}.
   *
   * @return nombre d'incohérences détectées
   */
  public int verifier() {
    final List<SalleAEnrichir> candidats = selectionnerCandidats();
    if (candidats.isEmpty()) {
      log.info("coherence:wikidata — aucun candidat");
      return 0;
    }

    final Map<UUID, EnrichissementSalle> resultats = wikidata.enrichirLot(candidats);
    final List<UUID> salleIds = candidats.stream().map(SalleAEnrichir::id).toList();
    final Map<UUID, List<String>> villesConcertsReelles = chargerVillesConcertsReelles(salleIds);
    final Map<UUID, String> signaturesDejaSignalees = chargerSignaturesDejaSignalees(salleIds);

    int incoherences = 0;
    for (SalleAEnrichir salle : candidats) {
      final EnrichissementSalle enrichissement = resultats.get(salle.id());
      if (enrichissement == null) continue;
      final String communeWikidata = enrichissement.ville();
      if (communeWikidata == null || communeWikidata.isBlank()) continue;
      final Coherence coherence =
          evaluerCoherence(salle, communeWikidata, villesConcertsReelles.get(salle.id()));
      final String signatureConnue = signaturesDejaSignalees.get(salle.id());
      if (coherence == Coherence.COHERENTE) {
        if (signatureConnue != null) memoriserSignature(salle.id(), null);
        continue;
      }
      incoherences++;
      final boolean partielle = coherence == Coherence.INCOHERENTE_PARTIELLE;
      final String signature = communeWikidata + "|" + partielle;
      if (signature.equals(signatureConnue)) continue;
      memoriserSignature(salle.id(), signature);
      loggerIncoherence(salle, communeWikidata, partielle);
    }
    log.info("coherence:wikidata candidats={} incoherences={}", candidats.size(), incoherences);
    return incoherences;
  }

  /**
   * Revalide le type Wikidata de tous les {@code wikidata_id} déjà stockés contre les types actuellement
   * exclus par {@link WikidataSalleSource} — un garde-fou de type introduit après coup ne
   * s'applique qu'aux nouvelles découvertes, jamais rétroactivement. Un type structurel invalide
   * (gare, arrêt de tram, bar, restaurant...) est un signal sûr, contrairement à une incohérence
   * de ville qui peut avoir une cause légitime : {@code wikidata_id} est donc remis à {@code null}
   * directement, ce qui permettra une redécouverte au prochain cycle nocturne ({@code COALESCE} ne
   * réécrit jamais un {@code wikidata_id} déjà posé). {@code ville}/{@code nom_affiche} restent inchangés.
   *
   * @return nombre de {@code wikidata_id} invalidés
   */
  public int revaliderTypes() {
    final List<String> wikidataIds = selectionnerTousLesWikidataId();
    if (wikidataIds.isEmpty()) {
      log.info("coherence:wikidata:types — aucun candidat");
      return 0;
    }

    final Set<String> invalides = wikidata.typesInvalides(wikidataIds);
    for (String qid : invalides) {
      invaliderWikidataId(qid);
    }
    log.info(
        "coherence:wikidata:types candidats={} invalides={}", wikidataIds.size(), invalides.size());
    return invalides.size();
  }

  private List<String> selectionnerTousLesWikidataId() {
    return jdbc.queryForList(
        "SELECT wikidata_id FROM salles WHERE wikidata_id IS NOT NULL", Map.of(), String.class);
  }

  private void invaliderWikidataId(final String qid) {
    jdbc.update(
        "UPDATE salles SET wikidata_id = NULL WHERE wikidata_id = :qid", Map.of("qid", qid));
    logsAdminWorker.inserer(
        "warn",
        "coherence-wikidata",
        "wikidata_id invalidé — type Wikidata désormais exclu (gare/tram/administratif/commerce)"
            + " wikidataId="
            + qid,
        Map.of("wikidataId", qid));
  }

  /**
   * Charge, pour chaque salle candidate, la signature de la dernière incohérence déjà journalisée
   * (commune Wikidata + type de contamination), absente si jamais signalée ou si la dernière
   * incohérence signalée a depuis été résolue.
   */
  private Map<UUID, String> chargerSignaturesDejaSignalees(final List<UUID> salleIds) {
    if (salleIds.isEmpty()) return Map.of();
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

  // Map.of() refuse une valeur null (réinitialisation d'une signature résolue) — HashMap requis.
  private void memoriserSignature(final UUID salleId, final String signature) {
    final Map<String, Object> params = new HashMap<>();
    params.put("signature", signature);
    params.put("id", salleId.toString());
    jdbc.update(
        "UPDATE salles SET coherence_wikidata_signalee = :signature WHERE id = CAST(:id AS uuid)",
        params);
  }

  /**
   * Charge, pour chaque salle candidate, les villes réelles distinctes de ses concerts
   * ({@code concerts.ville}) — dérivées du code postal brut de la source, jamais touchées par
   * l'enrichissement Wikidata, contrairement à {@code salles.ville}.
   *
   * @param salleIds identifiants des salles candidates
   * @return villes réelles par salle, absente si la salle n'a aucun concert avec une ville connue
   *   (ex. tous les concerts passés déjà purgés)
   */
  private Map<UUID, List<String>> chargerVillesConcertsReelles(final List<UUID> salleIds) {
    if (salleIds.isEmpty()) return Map.of();
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
   * Résultat de {@link #evaluerCoherence} : distingue une contamination *partielle* (certains
   * concerts cohérents, d'autres non — une minorité de concerts mal rattachée à une salle par
   * ailleurs correctement enrichie) d'une incohérence *totale* (aucun concert cohérent, signe
   * classique d'un mauvais rapprochement Wikidata complet).
   */
  private enum Coherence {
    COHERENTE,
    INCOHERENTE_PARTIELLE,
    INCOHERENTE_TOTALE
  }

  /**
   * Évalue la cohérence de la commune Wikidata contre une source de vérité indépendante.
   *
   * <p>Si le mauvais rapprochement a corrompu {@code salles.ville} en même temps que {@code
   * wikidata_id} (constaté systématiquement dans les 3 cas confirmés), une comparaison contre
   * {@code salles.ville} seule trouve toujours les deux cohérents entre eux — angle mort total,
   * silencieux. {@code concerts.ville} sert de référence prioritaire quand elle est disponible ;
   * repli sur {@code salles.ville} uniquement pour une salle sans concert avec ville connue.
   *
   * <p>Toutes les villes réelles doivent être cohérentes pour ne rien journaliser — un simple
   * {@code anyMatch} devient aveugle dès qu'une salle mal fusionnée récupère ne serait-ce qu'un
   * concert cohérent, masquant silencieusement la contamination restante. Une contamination
   * partielle (au moins une ville cohérente, au moins une non) reste journalisée avec un message
   * dédié plutôt que d'être confondue avec un mauvais rapprochement total.
   *
   * @param salle salle candidate
   * @param communeWikidata commune Wikidata (P131) du {@code wikidata_id} déjà associé
   * @param villesConcertsReelles villes réelles des concerts rattachés, ou {@code null}/vide si
   *   aucune
   * @return la classification de cohérence
   */
  private static Coherence evaluerCoherence(
      final SalleAEnrichir salle, final String communeWikidata, final List<String> villesConcertsReelles) {
    if (villesConcertsReelles != null && !villesConcertsReelles.isEmpty()) {
      final long nbCoherentes =
          villesConcertsReelles.stream().filter(v -> villesCoherentes(v, communeWikidata)).count();
      if (nbCoherentes == villesConcertsReelles.size()) return Coherence.COHERENTE;
      return nbCoherentes > 0 ? Coherence.INCOHERENTE_PARTIELLE : Coherence.INCOHERENTE_TOTALE;
    }
    return villesCoherentes(salle.ville(), communeWikidata)
        ? Coherence.COHERENTE
        : Coherence.INCOHERENTE_TOTALE;
  }

  // une comparaison texte-à-texte stricte ville/commune Wikidata génère du bruit quasi
  // systématique sur toute salle parisienne/lyonnaise/marseillaise (arrondissement ≠ ville mère),
  // en zone urbaine dense (quartiers/districts informels sans commune propre, ex. Bercy, La
  // Défense) ou sur une commune fusionnée/intercommunalité (Wikidata garde parfois l'ancien nom
  // d'une des communes d'origine, ou référence l'intercommunalité). Cette normalisation est
  // volontairement locale à ce contrôle *a posteriori* : {@link
  // WikidataSalleSource#villeIncompatible}
  // reste inchangé, il bloque le matching *au moment de l'enrichissement* où une
  // comparaison plus permissive réduirait la couverture.
  private static final Pattern ARRONDISSEMENT =
      Pattern.compile("^(\\d{1,2})(?:e|er|eme|ieme)? +arrondissement de (paris|lyon|marseille)$");

  // Quartiers/districts informels ne correspondant à aucune commune administrative, rattachés à
  // une ou plusieurs communes possibles (ex. La Défense chevauche Nanterre/Puteaux/Courbevoie).
  private static final Map<String, Set<String>> QUARTIERS_EQUIVALENTS =
      Map.of(
          "bercy", Set.of("paris"),
          "la defense", Set.of("nanterre", "puteaux", "courbevoie", "paris"),
          "st germain l auxerrois", Set.of("paris"),
          "notre dame", Set.of("paris"),
          "chaussee d antin", Set.of("paris"),
          // Quartier administratif du 12e arrondissement de Paris où se trouve l'Opéra Bastille.
          "quinze vingts", Set.of("paris"));

  // Longueur minimale pour le repli par containment (commune fusionnée/intercommunalité, ex.
  // « Pierre-Bénite » ⊂ « Oullins-Pierre-Bénite », « Orange » ⊂ « communauté de communes du Pays
  // d'Orange en Provence ») — évite un containment trivial sur un token court.
  private static final int LONGUEUR_MIN_CONTAINMENT = 5;

  private static boolean villesCoherentes(final String villeSalle, final String communeWikidata) {
    final String villeSalleNorm = Normalisation.normaliserVille(villeSalle);
    final String communeNorm = Normalisation.normaliserVille(communeWikidata);
    if (villeSalleNorm.equals(communeNorm)) return true;
    if (!Collections.disjoint(canonicaliser(villeSalleNorm), canonicaliser(communeNorm))) {
      return true;
    }
    return containmentCoherent(villeSalleNorm, communeNorm);
  }

  private static Set<String> canonicaliser(final String villeNorm) {
    final Matcher matcher = ARRONDISSEMENT.matcher(villeNorm);
    if (matcher.matches()) return Set.of(matcher.group(2));
    final Set<String> quartier = QUARTIERS_EQUIVALENTS.get(villeNorm);
    if (quartier != null) return quartier;
    return Set.of(villeNorm);
  }

  private static boolean containmentCoherent(final String a, final String b) {
    if (a.length() < LONGUEUR_MIN_CONTAINMENT || b.length() < LONGUEUR_MIN_CONTAINMENT) {
      return false;
    }
    return a.contains(b) || b.contains(a);
  }

  /**
   * @param salle salle candidate
   * @param communeWikidata commune Wikidata (P131) du {@code wikidata_id} déjà associé
   * @param partielle {@code true} si seule une partie des concerts réels est incohérente — message
   *   distinct signalant une contamination partielle plutôt qu'un mauvais rapprochement total,
   *   pour ne pas induire un examinateur en erreur sur l'ampleur réelle
   */
  private void loggerIncoherence(final SalleAEnrichir salle, final String communeWikidata, final boolean partielle) {
    logsAdminWorker.inserer(
        "warn",
        "coherence-wikidata",
        (partielle
                ? "wikidata_id incohérent avec une partie des concerts (contamination partielle)"
                : "wikidata_id incohérent avec la ville")
            + " salle="
            + salle.nom()
            + " ville="
            + salle.ville()
            + " communeWikidata="
            + communeWikidata
            + " wikidataId="
            + salle.wikidataId(),
        Map.of(
            "salleId", salle.id().toString(),
            "wikidataId", salle.wikidataId(),
            "ville", salle.ville(),
            "communeWikidata", communeWikidata,
            "partielle", partielle));
  }

  private List<SalleAEnrichir> selectionnerCandidats() {
    return jdbc.query(
        """
        SELECT id, nom, nom_norm, ville, dept, wikidata_id
        FROM salles
        WHERE wikidata_id IS NOT NULL AND ville IS NOT NULL AND ville != ''
        """,
        Map.of(),
        (rs, _) ->
            new SalleAEnrichir(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getString("nom_norm"),
                rs.getString("ville"),
                rs.getString("dept"),
                rs.getString("wikidata_id")));
  }
}
