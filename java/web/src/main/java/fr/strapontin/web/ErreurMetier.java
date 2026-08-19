/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

/// Exception non-vérifiée pour les violations de règles métier.
///
/// <p>Interceptée par {@link GlobalExceptionHandler} qui retourne {@code {"erreur": "..."}} avec le
/// code HTTP porté par l'exception (400 par défaut).
///
/// @since 2.1.0
/// @author Kwaite
public class ErreurMetier extends RuntimeException {

  @java.io.Serial private static final long serialVersionUID = 1L;

  private final int status;

  /// Crée une erreur métier HTTP 400.
  ///
  /// @param message message retourné dans la réponse JSON
  public ErreurMetier(String message) {
    this(message, 400);
  }

  /// Crée une erreur métier avec un code HTTP personnalisé.
  ///
  /// @param message message retourné dans la réponse JSON
  /// @param status code HTTP (ex. 400, 422, 429)
  public ErreurMetier(String message, int status) {
    super(message);
    this.status = status;
  }

  /// @return le code HTTP à retourner au client
  public int getStatus() {
    return status;
  }
}
