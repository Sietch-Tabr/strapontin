/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

/**
 * Utilitaires partagés entre {@link EnrichissementSallesService} et {@link
 * EnrichissementArtistesService} — jusqu'ici dupliqués verbatim dans les deux classes.
 *
 * @since 2.8.7
 * @author Kwaite
 */
final class EnrichissementUtils {

  private EnrichissementUtils() {}

  /**
   * Met en majuscule la première lettre d'un nom.
   *
   * @param nom nom à capitaliser
   * @return le nom avec sa première lettre en majuscule, inchangé si vide
   */
  /* package-private : appelé par les sources d'enrichissement du même package. */
  static String capitaliser(final String nom) {
    return nom.isEmpty() ? nom : Character.toUpperCase(nom.charAt(0)) + nom.substring(1);
  }

  /**
   * Attend entre deux lots d'enrichissement, pour espacer les appels aux API externes.
   *
   * @param delaiMs délai d'attente en millisecondes
   */
  // Thread.sleep est la seule primitive de cadencement synchrone bloquant disponible — pas un
  // contexte de requête J2EE (appel HTTP sortant en job Quartz), la justification de la règle ne
  // s'applique pas ici.
  @SuppressWarnings("PMD.DoNotUseThreads")
  /* package-private : appelé par les sources d'enrichissement du même package. */
  static void attendreEntreLots(final long delaiMs) {
    try {
      Thread.sleep(delaiMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
