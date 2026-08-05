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

/// Contrôle de cohérence *a posteriori* entre `salles.wikidata_id` (déjà connu) et `salles.ville`
/// (découvert en marge de l'investigation) : 7 salles sur 12 avaient un `wikidata_id`
/// pointant vers une entité totalement étrangère — Q-ID matché sur un homonyme d'une autre ville
/// (ex. « Theatre Municipal » de Béthune rattaché au musée des Beaux-Arts de Nancy) — jamais
/// détecté faute de contrôle après coup, uniquement trouvé par un audit manuel ponctuel.
///
/// {@link WikidataSalleSource#enrichirLot} rejette déjà ce type d'incohérence *au moment du
/// matching* (`villeIncompatible`) mais seulement quand `salle.ville` et la commune du
/// candidat sont toutes les deux connues — une ville locale inconnue ou fausse au moment de
/// l'enrichissement (le cas des 7 salles ci-dessus) ne bloque rien, choix assumé pour ne pas
/// réduire la couverture d'enrichissement des salles légitimement sans ville. Ce service ne change
/// rien à ce comportement : il journalise seulement les incohérences déjà présentes en base pour un
/// examen manuel ultérieur, en réutilisant la commune déjà récupérée par le mode batch de {@link
/// WikidataSalleSource} (aucune requête SPARQL supplémentaire par rapport à un enrichissement
/// classique à `wikidata_id` connu).
///
/// <p> : la comparaison initiale contre `salles.ville` seule est aveugle par construction
/// quand le mauvais rapprochement a corrompu `ville` en même temps que `wikidata_id` (la mauvaise
/// entité écrase les deux au même moment) — les deux valeurs corrompues sont alors toujours
/// cohérentes entre elles. `concerts.ville`, dérivée du code postal brut de la source et jamais
/// touchée par l'enrichissement, sert désormais de référence prioritaire quand elle est disponible
/// ; `salles.ville` reste le repli pour une salle sans concert avec ville connue (ex. tous ses
/// concerts passés déjà purgés).
///
/// @since 2.7.5
/// @author Kwaite
@Service
public class CoherenceWikidataSalleService {

  private static final Logger log = LoggerFactory.getLogger(CoherenceWikidataSalleService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final WikidataSalleSource wikidata;
  private final LogsAdminWorker logsAdminWorker;

  public CoherenceWikidataSalleService(
      NamedParameterJdbcTemplate jdbc,
      WikidataSalleSource wikidata,
      LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.wikidata = wikidata;
    this.logsAdminWorker = logsAdminWorker;
  }

  /// Vérifie la cohérence ville/wikidata_id de toutes les salles ayant déjà un {@code wikidata_id}
  /// et une {@code ville} connus, journalise chaque incohérence trouvée dans {@code logs_admin}.
  ///
  /// @return nombre d'incohérences détectées
  public int verifier() {
    List<SalleAEnrichir> candidats = selectionnerCandidats();
    if (candidats.isEmpty()) {
      log.info("coherence:wikidata — aucun candidat");
      return 0;
    }

    Map<UUID, EnrichissementSalle> resultats = wikidata.enrichirLot(candidats);
    Map<UUID, List<String>> villesConcertsReelles =
        chargerVillesConcertsReelles(candidats.stream().map(SalleAEnrichir::id).toList());

    int incoherences = 0;
    for (SalleAEnrichir salle : candidats) {
      EnrichissementSalle enrichissement = resultats.get(salle.id());
      if (enrichissement == null) continue;
      String communeWikidata = enrichissement.ville();
      if (communeWikidata == null || communeWikidata.isBlank()) continue;
      Coherence coherence =
          evaluerCoherence(salle, communeWikidata, villesConcertsReelles.get(salle.id()));
      if (coherence == Coherence.COHERENTE) continue;
      incoherences++;
      loggerIncoherence(salle, communeWikidata, coherence == Coherence.INCOHERENTE_PARTIELLE);
    }
    log.info("coherence:wikidata candidats={} incoherences={}", candidats.size(), incoherences);
    return incoherences;
  }

  /// Charge, pour chaque salle candidate, les villes réelles distinctes de ses concerts
  /// (`concerts.ville`) — dérivées du code postal brut de la source, jamais touchées par
  /// l'enrichissement Wikidata, contrairement à `salles.ville`.
  ///
  /// @param salleIds identifiants des salles candidates
  /// @return villes réelles par salle, absente si la salle n'a aucun concert avec une ville connue
  ///   (ex. tous les concerts passés déjà purgés)
  private Map<UUID, List<String>> chargerVillesConcertsReelles(List<UUID> salleIds) {
    if (salleIds.isEmpty()) return Map.of();
    Map<UUID, List<String>> parSalle = new HashMap<>();
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

  /// Résultat de {@link #evaluerCoherence} : distingue une contamination *partielle* (certains
  /// concerts cohérents, d'autres non — une minorité de concerts mal rattachée à une salle par
  /// ailleurs correctement enrichie) d'une incohérence *totale* (aucun concert cohérent, signe
  /// classique d'un mauvais rapprochement Wikidata complet).
  private enum Coherence {
    COHERENTE,
    INCOHERENTE_PARTIELLE,
    INCOHERENTE_TOTALE
  }

  /// Évalue la cohérence de la commune Wikidata contre une source de vérité indépendante.
  ///
  /// <p>Si le mauvais rapprochement a corrompu {@code salles.ville} en même temps que {@code
  /// wikidata_id} (constaté systématiquement dans les 3 cas confirmés), une comparaison contre
  /// {@code salles.ville} seule trouve toujours les deux cohérents entre eux — angle mort total,
  /// silencieux. {@code concerts.ville} sert de référence prioritaire quand elle est disponible ;
  /// repli sur {@code salles.ville} uniquement pour une salle sans concert avec ville connue.
  ///
  /// <p>Toutes les villes réelles doivent être cohérentes pour ne rien journaliser — un simple
  /// {@code anyMatch} devient aveugle dès qu'une salle mal fusionnée récupère ne serait-ce qu'un
  /// concert cohérent, masquant silencieusement la contamination restante. Une contamination
  /// partielle (au moins une ville cohérente, au moins une non) reste journalisée avec un message
  /// dédié plutôt que d'être confondue avec un mauvais rapprochement total.
  ///
  /// @param salle salle candidate
  /// @param communeWikidata commune Wikidata (P131) du `wikidata_id` déjà associé
  /// @param villesConcertsReelles villes réelles des concerts rattachés, ou {@code null}/vide si
  ///   aucune
  /// @return la classification de cohérence
  private static Coherence evaluerCoherence(
      SalleAEnrichir salle, String communeWikidata, List<String> villesConcertsReelles) {
    if (villesConcertsReelles != null && !villesConcertsReelles.isEmpty()) {
      long nbCoherentes =
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

  private static boolean villesCoherentes(String villeSalle, String communeWikidata) {
    String villeSalleNorm = Normalisation.normaliserVille(villeSalle);
    String communeNorm = Normalisation.normaliserVille(communeWikidata);
    if (villeSalleNorm.equals(communeNorm)) return true;
    if (!Collections.disjoint(canonicaliser(villeSalleNorm), canonicaliser(communeNorm))) {
      return true;
    }
    return containmentCoherent(villeSalleNorm, communeNorm);
  }

  private static Set<String> canonicaliser(String villeNorm) {
    Matcher matcher = ARRONDISSEMENT.matcher(villeNorm);
    if (matcher.matches()) return Set.of(matcher.group(2));
    Set<String> quartier = QUARTIERS_EQUIVALENTS.get(villeNorm);
    if (quartier != null) return quartier;
    return Set.of(villeNorm);
  }

  private static boolean containmentCoherent(String a, String b) {
    if (a.length() < LONGUEUR_MIN_CONTAINMENT || b.length() < LONGUEUR_MIN_CONTAINMENT) {
      return false;
    }
    return a.contains(b) || b.contains(a);
  }

  /// @param salle salle candidate
  /// @param communeWikidata commune Wikidata (P131) du `wikidata_id` déjà associé
  /// @param partielle {@code true} si seule une partie des concerts réels est incohérente — message
  ///   distinct signalant une contamination partielle plutôt qu'un mauvais rapprochement total,
  ///   pour ne pas induire un examinateur en erreur sur l'ampleur réelle
  private void loggerIncoherence(SalleAEnrichir salle, String communeWikidata, boolean partielle) {
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
