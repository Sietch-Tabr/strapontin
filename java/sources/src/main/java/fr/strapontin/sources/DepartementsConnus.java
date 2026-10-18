/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.Set;

/**
 * Départements que les sources interrogent : métropole (la Corse sous
 * le code 20) et outre-mer.
 * @since 3.0.6
 * @author Kwaite
 */
public final class DepartementsConnus {

  private DepartementsConnus() {}

  /**
   * @return codes des départements couverts par les requêtes par région
   */
  public static Set<String> codes() {
    return CentresDepartement.PAR_DEPT.keySet();
  }
}
