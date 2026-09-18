/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import java.io.Serial;

/**
 * Exception non-vérifiée pour les conflits de ressource déjà existante —
 * interceptée par {@link GlobalExceptionHandler} → HTTP 409. Pour les
 * doublons métier explicites (artiste/salle déjà suivi), distincts d'une
 * {@link org.springframework.dao.DuplicateKeyException} contrainte DB.
 * @since 2.1.0
 * @author Kwaite
 */
public class RessourceDejaExistante extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param message message retourné dans la réponse JSON
   */
  public RessourceDejaExistante(final String message) {
    super(message);
  }
}
