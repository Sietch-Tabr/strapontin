/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

/**
 * Utilitaires partagés entre contrôleurs REST — jusqu'ici dupliqués verbatim
 * dans une douzaine de contrôleurs ({@code erreur}) et cinq d'entre eux ({@code
 * parseUuid}).
 *
 * @since 2.8.7
 * @author Kwaite
 */
public final class ControllerUtils {

  private ControllerUtils() {}

  /**
   * Construit une réponse d'erreur JSON {@code {"erreur": message}}.
   *
   * @param status code de statut HTTP
   * @param message message d'erreur
   * @return la réponse HTTP
   */
  public static ResponseEntity<Map<String, Object>> erreur(final int status, final String message) {
    return ResponseEntity.status(status).body(Map.of("erreur", message));
  }

  /**
   * Parse un UUID, sans lever d'exception sur un format invalide.
   *
   * @param valeur chaîne à parser
   * @return l'UUID, ou {@code null} si {@code valeur} est absente ou mal formée
   */
  public static UUID parseUuid(final String valeur) {
    if (valeur == null || valeur.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(valeur);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
