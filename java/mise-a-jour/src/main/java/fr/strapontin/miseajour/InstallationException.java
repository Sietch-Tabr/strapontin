/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

/**
 * Échec d'une étape de la mise à jour. Le message, en français, est écrit
 * dans l'état lu par l'administration : il ne contient ni chemin interne
 * ni secret.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class InstallationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message motif de l'échec
   */
  public InstallationException(final String message) {
    super(message);
  }

  /**
   * @param message motif de l'échec
   * @param cause erreur d'origine
   */
  public InstallationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
