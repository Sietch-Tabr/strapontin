/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

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
   * Utilisateur de la session courante.
   *
   * @param auth authentification de la requête
   * @return l'utilisateur connecté
   * @throws IllegalStateException si le principal n'est pas un utilisateur
   */
  public static UtilisateurDetails utilisateur(final Authentication auth) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails details)) {
      throw new IllegalStateException("Principal inattendu");
    }
    return details;
  }

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
