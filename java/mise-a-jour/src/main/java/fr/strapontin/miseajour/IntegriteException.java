/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

/**
 * Un fichier téléchargé ne correspond pas à son empreinte attendue.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public class IntegriteException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message fichier concerné et cause du refus
   */
  public IntegriteException(final String message) {
    super(message);
  }
}
