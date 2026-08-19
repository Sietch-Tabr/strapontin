/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

/// Exception non-vérifiée pour les conflits de ressource déjà existante.
///
/// <p>Interceptée par {@link GlobalExceptionHandler} → HTTP 409. Utiliser pour les doublons métier
/// explicites (artiste déjà suivi, salle déjà dans la liste, etc.), distincts d'une {@link
/// org.springframework.dao.DuplicateKeyException} contrainte DB.
///
/// @since 2.1.0
/// @author Kwaite
public class RessourceDejaExistante extends RuntimeException {

  @java.io.Serial private static final long serialVersionUID = 1L;

  /// @param message message retourné dans la réponse JSON
  public RessourceDejaExistante(String message) {
    super(message);
  }
}
