/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.List;

/**
 * Capacité optionnelle d'une source à remonter les produits écartés de ses
 * récupérations par artiste : une date déjà enregistrée à tort sous l'un
 * d'eux peut alors être signalée sans attendre ses absences.
 * @since 3.0.3
 * @author Kwaite
 */
@FunctionalInterface
public interface SourceAvecProduitsEcartes {

  /**
   * @return les produits écartés depuis le dernier prélèvement, vidés ensuite
   */
  List<ProduitEcarte> preleverProduitsEcartes();
}
