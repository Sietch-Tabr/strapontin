/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/// Utilitaires de manipulation de chaînes partagés entre modules.
///
/// @since 2.2.3
/// @author Kwaite
public final class UtilitairesChaine {

  private UtilitairesChaine() {
    // Classe utilitaire — pas d'instanciation
  }

  /// Échappe une chaîne pour insertion sûre dans une valeur JSON construite à la main.
  ///
  /// Échappe les antislashs, guillemets doubles et retours à la ligne. Destiné aux
  /// petits payloads JSON assemblés sans dépendance Jackson (notifications push).
  ///
  /// @param s la chaîne à échapper (non null)
  /// @return la chaîne échappée
  /// @since 2.2.3
  public static String echapperJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }
}
