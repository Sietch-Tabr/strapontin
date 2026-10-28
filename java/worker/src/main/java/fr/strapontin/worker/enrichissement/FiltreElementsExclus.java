/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.SourceEnrichissementSalle;
import fr.strapontin.sources.WikidataSalleSource;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Écarte, avant affectation, les éléments Wikidata dont le type est exclu
 * (gare, voie, lieu détruit) proposés par une source d'enrichissement.
 * @since 3.1.1
 * @author Kwaite
 */
final class FiltreElementsExclus {

  private static final Logger LOG = LoggerFactory.getLogger(FiltreElementsExclus.class);
  private static final String CLE_WIKIDATA_ID = "wikidataId";

  private final List<SourceEnrichissementSalle> sources;
  private final LogsAdminWorker logsAdminWorker;

  /* package-private : instancié par EnrichissementSallesService. */
  FiltreElementsExclus(
      final List<SourceEnrichissementSalle> sources, final LogsAdminWorker logsAdminWorker) {
    this.sources = sources;
    this.logsAdminWorker = logsAdminWorker;
  }

  /* package-private : appelé par EnrichissementSallesService. */
  // Un élément proposé par OpenStreetMap (une gare pour un lieu installé dans
  // son bâtiment)
  // n'est jamais affecté puis retiré par la revalidation : son type est
  // contrôlé avant.
  void ecarter(final List<SalleAEnrichir> lot, final Map<UUID, EnrichissementSalle> accumule) {
    final Set<String> propose =
        accumule.values().stream()
            .map(EnrichissementSalle::wikidataId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (propose.isEmpty()) {
      return;
    }
    final Set<String> exclus = typesInvalides(propose);
    if (exclus.isEmpty()) {
      return;
    }
    for (final SalleAEnrichir salle : lot) {
      final EnrichissementSalle enrichissement = accumule.get(salle.id());
      if (enrichissement != null && exclus.contains(enrichissement.wikidataId())) {
        accumule.remove(salle.id());
        logsAdminWorker.inserer(
            "warn",
            "enrichissement",
            "élément Wikidata de type exclu proposé, écarté salle="
                + salle.nom()
                + " wikidataId="
                + enrichissement.wikidataId(),
            Map.of("salleId", salle.id().toString(), CLE_WIKIDATA_ID, enrichissement.wikidataId()));
      }
    }
  }

  private Set<String> typesInvalides(final Set<String> qids) {
    for (final SourceEnrichissementSalle source : sources) {
      if (source instanceof WikidataSalleSource wikidata) {
        try {
          return wikidata.typesInvalides(List.copyOf(qids));
        } catch (RuntimeException e) {
          LOG.warn("contrôle du type des éléments proposés impossible : {}", e.getMessage());
          return Set.of();
        }
      }
    }
    return Set.of();
  }
}
