/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

/// Résultat d'enrichissement d'une salle par une {@link SourceEnrichissementSalle}.
///
/// Tous les champs sont nullable — une source ne renseigne que ce qu'elle sait produire
/// (ex. Overpass ne fournit pas toujours `wikidataId`, Wikidata ne fournit pas `capacite`).
///
/// @param wikidataId Q-ID Wikidata découvert
/// @param nomAffiche nom canonique français
/// @param ville      commune associée
/// @param website    site officiel
/// @param capacite   capacité d'accueil
/// @since 2.6.0
/// @author Kwaite
public record EnrichissementSalle(
    String wikidataId, String nomAffiche, String ville, String website, Integer capacite) {}
