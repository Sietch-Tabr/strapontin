/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.enrichissement;

import java.util.List;

/**
 * Résultat d'enrichissement d'un artiste — tous nullable sauf {@code
 * aliasWikidata} (liste vide si absent) ; {@code ticketmasterIdLegacy}
 * porte le P7354 Wikidata, un identifiant numérique legacy distinct du
 * format attractionId Discovery API réellement stocké côté TM.
 * @since 2.6.0
 * @author Kwaite
 */
public record EnrichissementArtiste(
    String wikidataId,
    String nomAffiche,
    String deezerId,
    String ticketmasterIdLegacy,
    List<String> aliasWikidata) {

  // Remplace un aliasWikidata null par une liste vide.
  public EnrichissementArtiste {
    if (aliasWikidata == null) {
      aliasWikidata = List.of();
    }
  }
}
