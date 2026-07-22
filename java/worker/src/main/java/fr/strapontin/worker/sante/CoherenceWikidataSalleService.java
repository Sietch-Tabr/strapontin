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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Contrôle de cohérence *a posteriori* entre `salles.wikidata_id` (déjà connu) et `salles.ville`
/// (#1005, découvert en marge de l'investigation #1004) : 7 salles sur 12 avaient un
/// `wikidata_id` pointant vers une entité totalement étrangère — Q-ID matché sur un homonyme
/// d'une autre ville (ex. « Theatre Municipal » de Béthune rattaché au musée des Beaux-Arts de
/// Nancy) — jamais détecté faute de contrôle après coup, uniquement trouvé par un audit manuel
/// ponctuel.
///
/// {@link WikidataSalleSource#enrichirLot} rejette déjà ce type d'incohérence *au moment du
/// matching* (`villeIncompatible`, #1002) mais seulement quand `salle.ville()` et la commune du
/// candidat sont toutes les deux connues — une ville locale inconnue ou fausse au moment de
/// l'enrichissement (le cas des 7 salles ci-dessus) ne bloque rien, choix assumé pour ne pas
/// réduire la couverture d'enrichissement des salles légitimement sans ville. Ce service ne
/// change rien à ce comportement : il journalise seulement les incohérences déjà présentes en
/// base pour un examen manuel ultérieur, en réutilisant la commune déjà récupérée par le mode
/// batch de {@link WikidataSalleSource} (aucune requête SPARQL supplémentaire par rapport à un
/// enrichissement classique à `wikidata_id` connu).
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

    int incoherences = 0;
    for (SalleAEnrichir salle : candidats) {
      EnrichissementSalle enrichissement = resultats.get(salle.id());
      if (enrichissement == null) continue;
      String communeWikidata = enrichissement.ville();
      if (communeWikidata == null || communeWikidata.isBlank()) continue;
      if (villesCoherentes(salle.ville(), communeWikidata)) continue;
      incoherences++;
      loggerIncoherence(salle, communeWikidata);
    }
    log.info("coherence:wikidata candidats={} incoherences={}", candidats.size(), incoherences);
    return incoherences;
  }

  // #1009 : une comparaison texte-à-texte stricte ville/commune Wikidata génère du bruit quasi
  // systématique sur toute salle parisienne/lyonnaise/marseillaise (arrondissement ≠ ville mère),
  // en zone urbaine dense (quartiers/districts informels sans commune propre, ex. Bercy, La
  // Défense) ou sur une commune fusionnée/intercommunalité (Wikidata garde parfois l'ancien nom
  // d'une des communes d'origine, ou référence l'intercommunalité). Cette normalisation est
  // volontairement locale à ce contrôle *a posteriori* : {@link
  // WikidataSalleSource#villeIncompatible}
  // (#1002) reste inchangé, il bloque le matching *au moment de l'enrichissement* où une
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
          "chaussee d antin", Set.of("paris"));

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

  private void loggerIncoherence(SalleAEnrichir salle, String communeWikidata) {
    logsAdminWorker.inserer(
        "warn",
        "coherence-wikidata",
        "wikidata_id incohérent avec la ville salle="
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
            "communeWikidata", communeWikidata));
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
