/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;

/**
 * Entrée de la vue unifiée des signalements ; la catégorie (mécanisme
 * d'origine) détermine l'action possible, le type est décrit par
 * {@link CatalogueSignalements}. detecteLe et details peuvent être null.
 * @since 2.9.0
 * @author Kwaite
 */
public record EntreeSignalement(
    String categorie,
    String id,
    String type,
    EntiteSignalee entite,
    String confiance,
    Instant detecteLe,
    String details) {

  /**
   * Entité concernée : salle, artiste, utilisateur, ou invariant (anomalie
   * globale, sans nom).
   */
  public record EntiteSignalee(String type, String id, String nom) {}
}
