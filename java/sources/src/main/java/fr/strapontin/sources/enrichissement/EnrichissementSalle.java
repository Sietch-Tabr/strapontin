/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.enrichissement;

/**
 * Résultat d'enrichissement d'une salle — tous nullable (une source ne
 * renseigne que ce qu'elle sait produire).
 * @since 2.6.0
 * @author Kwaite
 */
public record EnrichissementSalle(
    String wikidataId,
    String nomAffiche,
    String ville,
    String website,
    Integer capacite,
    Double latitude,
    Double longitude) {

  /**
   * Constructeur pour une source ne fournissant pas de coordonnées.
   */
  public EnrichissementSalle(
      final String wikidataId,
      final String nomAffiche,
      final String ville,
      final String website,
      final Integer capacite) {
    this(wikidataId, nomAffiche, ville, website, capacite, null, null);
  }
}
