/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;

/**
 * Capacité optionnelle d'une source ou d'un résolveur à exposer un bilan agrégé
 * d'appels et d'erreurs depuis le dernier prélèvement, pour une détection de
 * panne générique côté worker.
 *
 * @since 2.7.0
 * @author Kwaite
 */
@FunctionalInterface
public interface SourceAvecBilanFetch {

  /**
   * Retourne le bilan cumulé depuis le dernier prélèvement et remet les
   * compteurs à zéro.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  BilanFetch preleverBilan();

  /**
   * Nombre minimal d'appels pour qu'un taux d'échec de 100% escalade en
   * {@code error} plutôt que {@code warn} — détail : CLAUDE.md,
   * Enrichissement Wikidata/OSM.
   * @return seuil minimal d'appels, {@link
   *     BilanFetch#SEUIL_APPELS_MIN_ALERTE_DEFAUT} par défaut
   */
  default int seuilAppelsMinAlerte() {
    return BilanFetch.SEUIL_APPELS_MIN_ALERTE_DEFAUT;
  }
}
