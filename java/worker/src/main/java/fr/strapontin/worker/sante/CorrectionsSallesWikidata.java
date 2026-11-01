/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.enrichissement.EnrichissementSalle;
import fr.strapontin.sources.enrichissement.SalleAEnrichir;
import fr.strapontin.sources.enrichissement.WikidataSalleSource;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Corrections de salles liées à Wikidata : position manquante reprise de P625,
 * ville alignée sur la commune confirmée par les concerts, élément invalidé.
 * @since 3.1.1
 * @author Kwaite
 */
final class CorrectionsSallesWikidata {

  private static final Logger LOG = LoggerFactory.getLogger(CorrectionsSallesWikidata.class);
  private static final String SOURCE_LOG = "coherence-wikidata";
  private static final String CLE_WIKIDATA_ID = "wikidataId";

  private final NamedParameterJdbcTemplate jdbc;
  private final WikidataSalleSource wikidata;
  private final LogsAdminWorker logsAdminWorker;

  /* package-private : instancié par CoherenceWikidataSalleService. */
  CorrectionsSallesWikidata(
      final NamedParameterJdbcTemplate jdbc,
      final WikidataSalleSource wikidata,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.wikidata = wikidata;
    this.logsAdminWorker = logsAdminWorker;
  }

  /* package-private : appelé par CoherenceWikidataSalleService. */
  /**
   * Pose la position de l'élément Wikidata (P625) d'une salle qui en porte un
   * sans avoir aucune
   * position : sans position, elle échappe au rapprochement par position et aux
   * contrôles
   * d'écart.
   * @return nombre de salles positionnées
   */
  int poserPositionsManquantes() {
    final List<SalleAEnrichir> salles =
        jdbc.query(
            "SELECT id, nom, nom_norm, ville, dept, wikidata_id, latitude, longitude FROM salles"
                + " WHERE wikidata_id IS NOT NULL AND latitude IS NULL AND longitude IS NULL",
            Map.of(),
            (rs, _) -> CoherenceWikidataSalleService.versSalleAEnrichir(rs));
    if (salles.isEmpty()) {
      return 0;
    }
    final Map<UUID, EnrichissementSalle> elements = wikidata.enrichirLot(salles);
    int posees = 0;
    for (final SalleAEnrichir salle : salles) {
      final EnrichissementSalle element = elements.get(salle.id());
      if (element != null && element.latitude() != null && element.longitude() != null) {
        posees +=
            jdbc.update(
                "UPDATE salles SET latitude = :latitude, longitude = :longitude,"
                    + " origine_position = 'wikidata'"
                    + " WHERE id = :id AND latitude IS NULL AND longitude IS NULL",
                Map.of(
                    "latitude",
                    element.latitude(),
                    "longitude",
                    element.longitude(),
                    "id",
                    salle.id()));
      }
    }
    LOG.info(
        "coherence:wikidata:positions-manquantes candidates={} posees={}", salles.size(), posees);
    return posees;
  }

  // La ville d'une salle vient de la première billetterie qui l'a créée. Quand
  // Wikidata donne
  // une autre commune du même département et que les concerts de la salle
  // portent cette commune,
  // c'est la commune réelle (Le Transbordeur : Villeurbanne, non Lyon).
  /* package-private : appelé par CoherenceWikidataSalleService. */
  void corrigerVille(
      final SalleAEnrichir salle,
      final EnrichissementSalle enrichissement,
      final List<String> villesConcertsReelles) {
    final String commune = enrichissement.ville();
    if (commune == null
        || salle.ville() == null
        || salle.dept() == null
        || villesConcertsReelles == null
        || Normalisation.normaliserTexte(commune)
            .equals(Normalisation.normaliserTexte(salle.ville()))) {
      return;
    }
    final String communeNorm = Normalisation.normaliserTexte(commune);
    final boolean concertsAccordes =
        villesConcertsReelles.stream()
            .map(Normalisation::normaliserTexte)
            .allMatch(communeNorm::equals);
    if (!concertsAccordes) {
      return;
    }
    jdbc.update(
        "UPDATE salles SET ville = :ville WHERE id = :id",
        Map.of("ville", commune, "id", salle.id()));
    logsAdminWorker.inserer(
        "info",
        SOURCE_LOG,
        "ville de la salle alignée sur la commune Wikidata salle="
            + salle.nom()
            + " ancienne="
            + salle.ville()
            + " commune="
            + commune,
        Map.of("salleId", salle.id().toString(), "ancienneVille", salle.ville(), "ville", commune));
  }

  /* package-private : appelé par CoherenceWikidataSalleService. */
  void invaliderWikidataId(final String qid) {
    final List<String> salles =
        jdbc.queryForList(
            "SELECT nom FROM salles WHERE wikidata_id = :qid ORDER BY nom",
            Map.of("qid", qid),
            String.class);
    jdbc.update(
        // Coordonnées issues de la même entité (P625) : fausses aussi, et elles
        // serviraient sinon à rattacher par proximité GPS des lieux sans
        // rapport.
        "UPDATE salles SET wikidata_id = NULL, latitude = NULL, longitude = NULL,"
            + " origine_position = NULL"
            + " WHERE wikidata_id = :qid",
        Map.of("qid", qid));
    logsAdminWorker.inserer(
        "warn",
        SOURCE_LOG,
        "wikidata_id invalidé — type Wikidata exclu ou élément dissous (gare, tram, détruit,"
            + " administratif, commerce) wikidataId="
            + qid
            + " salle(s)="
            + String.join(", ", salles),
        Map.of(CLE_WIKIDATA_ID, qid, "salles", salles));
  }
}
