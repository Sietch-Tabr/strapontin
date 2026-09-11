/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.List;

/**
 * Résultat d'enrichissement d'un artiste par une {@link SourceEnrichissementArtiste}.
 *
 * Tous les champs sont nullable (sauf {@code aliasWikidata}, jamais {@code null} — liste vide si
 * absent) — une source ne renseigne que ce qu'elle sait produire. {@code ticketmasterAttractionId}
 * porte le P6262 Wikidata, une cross-validation indépendante de TM plutôt qu'un enrichissement
 * cosmétique.
 *
 * @param wikidataId Q-ID Wikidata découvert ou confirmé
 * @param nomAffiche nom canonique français
 * @param deezerId identifiant Deezer (P2722 Wikidata, ou confirmé par Deezer)
 * @param ticketmasterAttractionId attractionId TM porté par le Q-ID Wikidata (P6262)
 * @param aliasWikidata noms alternatifs connus (skos:altLabel), jamais {@code null}
 * @since 2.6.0
 * @author Kwaite
 */
public record EnrichissementArtiste(
    String wikidataId,
    String nomAffiche,
    String deezerId,
    String ticketmasterAttractionId,
    List<String> aliasWikidata) {

  public EnrichissementArtiste {
    if (aliasWikidata == null) aliasWikidata = List.of();
  }
}
