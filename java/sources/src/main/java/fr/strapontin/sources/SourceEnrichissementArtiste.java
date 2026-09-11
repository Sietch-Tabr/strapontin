/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Source capable d'enrichir un lot d'artistes avec des métadonnées canoniques.
 *
 * Ajouter une source d'enrichissement = créer une implémentation + un bean Spring dans
 * {@code EnrichissementConfiguration} (module worker), sans modifier l'orchestrateur. Aucun
 * parent commun avec {@link SourceEnrichissementSalle} : les deux hiérarchies évoluent
 * indépendamment.
 *
 * @since 2.6.0
 * @author Kwaite
 */
public interface SourceEnrichissementArtiste {

  /**
   * Identifiant technique de la source (ex. {@code "wikidata"}, {@code "deezer"}).
   *
   * @return identifiant de la source
   */
  String nom();

  /**
   * Enrichit un lot d'artistes. Les entrées sans résultat exploitable sont absentes de la map
   * retournée — l'appelant ne doit jamais supposer une clé présente pour chaque artiste du lot.
   *
   * @param artistes artistes à enrichir
   * @return résultats indexés par {@link ArtisteAEnrichir#id()}
   */
  Map<UUID, EnrichissementArtiste> enrichirLot(List<ArtisteAEnrichir> artistes);
}
