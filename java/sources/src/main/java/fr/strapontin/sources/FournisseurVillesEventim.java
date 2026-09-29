/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.List;

/**
 * Fournit les villes (orthographe Eventim) à interroger pour des départements —
 * l'API n'expose aucun filtre par département ni code postal ({@code query}
 * est ignoré), seulement {@code city_names}.
 *
 * @since 3.0.1
 * @author Kwaite
 */
@FunctionalInterface
public interface FournisseurVillesEventim {

  /**
   * @param depts codes département
   * @return villes à interroger, vide si aucune connue
   */
  List<String> villes(List<String> depts);
}
