/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.UUID;

/**
 * Artiste candidat à l'enrichissement Wikidata/Deezer ; {@code
 * spectacleNonMusical} signale un artiste classé théâtre ou divers (humoriste) :
 * Deezer ne le référence pas, un homonyme musicien ne doit pas lui être attribué.
 * @since 2.6.0
 * @author Kwaite
 */
public record ArtisteAEnrichir(
    UUID id,
    String nom,
    String nomNorm,
    String wikidataId,
    String deezerId,
    boolean spectacleNonMusical) {

  /**
   * Artiste sans indication de classification.
   * @param id identifiant de l'artiste
   * @param nom nom affiché
   * @param nomNorm nom normalisé
   * @param wikidataId identifiant Wikidata connu, ou {@code null}
   * @param deezerId identifiant Deezer connu, ou {@code null}
   */
  public ArtisteAEnrichir(
      final UUID id,
      final String nom,
      final String nomNorm,
      final String wikidataId,
      final String deezerId) {
    this(id, nom, nomNorm, wikidataId, deezerId, false);
  }
}
