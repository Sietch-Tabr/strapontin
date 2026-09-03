/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

/// Utilitaires partagés entre {@link EnrichissementSallesService} et {@link
/// EnrichissementArtistesService} — jusqu'ici dupliqués verbatim dans les deux classes.
///
/// @since 2.8.7
/// @author Kwaite
final class EnrichissementUtils {

  private EnrichissementUtils() {}

  /// Met en majuscule la première lettre d'un nom.
  ///
  /// @param nom nom à capitaliser
  /// @return le nom avec sa première lettre en majuscule, inchangé si vide
  static String capitaliser(String nom) {
    return nom.isEmpty() ? nom : Character.toUpperCase(nom.charAt(0)) + nom.substring(1);
  }

  /// Attend entre deux lots d'enrichissement, pour espacer les appels aux API externes.
  ///
  /// @param delaiMs délai d'attente en millisecondes
  static void attendreEntreLots(long delaiMs) {
    try {
      Thread.sleep(delaiMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
