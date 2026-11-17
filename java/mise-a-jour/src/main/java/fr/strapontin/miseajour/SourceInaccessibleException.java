/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

/**
 * La source des releases ne répond pas ou refuse un téléchargement.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public class SourceInaccessibleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message cause du refus
   */
  public SourceInaccessibleException(final String message) {
    super(message);
  }

  /**
   * @param message cause du refus
   * @param cause erreur d'origine
   */
  public SourceInaccessibleException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
