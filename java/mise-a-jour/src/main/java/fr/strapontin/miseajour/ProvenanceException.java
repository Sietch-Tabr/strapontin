/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

/**
 * La provenance d'un fichier n'est pas prouvée : aucune attestation, ou
 * attestation invalide, ou signée par une autre identité.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public class ProvenanceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message cause du refus
   */
  public ProvenanceException(final String message) {
    super(message);
  }

  /**
   * @param message cause du refus
   * @param cause erreur d'origine
   */
  public ProvenanceException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
