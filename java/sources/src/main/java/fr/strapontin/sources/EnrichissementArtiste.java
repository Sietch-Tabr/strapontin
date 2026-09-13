/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.List;

/**
 * Résultat d'enrichissement d'un artiste — tous nullable sauf {@code
 * aliasWikidata} (liste vide si absent) ; {@code
 * ticketmasterAttractionId} porte le P6262 Wikidata (cross-validation
 * indépendante de TM, pas cosmétique).
 * @since 2.6.0
 * @author Kwaite
 */
public record EnrichissementArtiste(
    String wikidataId,
    String nomAffiche,
    String deezerId,
    String ticketmasterAttractionId,
    List<String> aliasWikidata) {

  // Remplace un aliasWikidata null par une liste vide.
  public EnrichissementArtiste {
    if (aliasWikidata == null) {
      aliasWikidata = List.of();
    }
  }
}
