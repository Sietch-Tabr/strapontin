/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;

/**
 * Paramètres de requête de la liste du référentiel des salles, mêmes filtres
 * que {@code FiltresRechercheSalles}. Types boîtes : un paramètre absent
 * arrive {@code null} et reçoit son défaut ici ; {@code ids} sépare les
 * identifiants par des virgules ou les répète.
 * @since 3.0.6
 * @author Kwaite
 */
public record CriteresListeSalles(
    Integer page,
    String nom,
    String ville,
    String dept,
    String statut,
    Boolean wikidataManquant,
    List<String> ids,
    Boolean sansSource) {

  /** Applique les défauts des paramètres absents. */
  public CriteresListeSalles {
    page = page == null ? 0 : page;
    wikidataManquant = Boolean.TRUE.equals(wikidataManquant);
    sansSource = Boolean.TRUE.equals(sansSource);
    ids = ids == null ? List.of() : ids;
  }
}
