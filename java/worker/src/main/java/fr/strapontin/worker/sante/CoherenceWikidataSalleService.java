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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
      if (Normalisation.normaliserVille(salle.ville())
          .equals(Normalisation.normaliserVille(communeWikidata))) {
        continue;
      }
      incoherences++;
      loggerIncoherence(salle, communeWikidata);
    }
    log.info("coherence:wikidata candidats={} incoherences={}", candidats.size(), incoherences);
    return incoherences;
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
