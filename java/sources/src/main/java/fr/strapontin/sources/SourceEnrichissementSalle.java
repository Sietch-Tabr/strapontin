/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Source capable d'enrichir un lot de salles avec des métadonnées
 * canoniques — ajouter une source = implémentation + bean Spring dans
 * {@code EnrichissementConfiguration}, sans modifier l'orchestrateur.
 * @since 2.6.0
 * @author Kwaite
 */
public interface SourceEnrichissementSalle {

  /**
   * Identifiant technique de la source (ex. {@code "overpass"} ,
   * {@code "wikidata"} ).
   *
   * @return identifiant de la source
   */
  String nom();

  /**
   * Enrichit un lot de salles. Les entrées sans résultat exploitable sont
   * absentes de la map retournée — l'appelant ne doit jamais supposer une clé
   * présente pour chaque salle du lot.
   *
   * @param salles salles à enrichir
   * @return résultats indexés par {@link SalleAEnrichir#id()}
   */
  Map<UUID, EnrichissementSalle> enrichirLot(List<SalleAEnrichir> salles);
}
