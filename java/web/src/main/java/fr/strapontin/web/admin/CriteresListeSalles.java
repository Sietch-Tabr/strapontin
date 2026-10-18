/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;

/**
 * Paramètres de requête de la liste du référentiel des salles. Types boîtes :
 * un paramètre absent arrive {@code null} et reçoit son défaut ici.
 *
 * @param page numéro de page (0 par défaut)
 * @param nom filtre nom partiel
 * @param ville filtre ville partielle
 * @param dept filtre département exact
 * @param statut a_jour, a_verifier ou invalide
 * @param wikidataManquant ne garde que les salles sans wikidata_id
 * @param ids identifiants à isoler (séparés par des virgules ou répétés)
 * @param sansSource ne garde que les salles qu'aucune source n'alimente
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
