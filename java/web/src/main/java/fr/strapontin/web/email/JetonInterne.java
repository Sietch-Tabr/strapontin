/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.ResponseEntity;

/**
 * Contrôle du jeton {@code Authorization: Bearer <secret>} des endpoints internes, appelés par les
 * scripts du serveur sans session : un secret absent refuse tout, la comparaison est à temps
 * constant. Une seule définition pour tous les endpoints internes.
 * @since 3.1.4
 * @author Kwaite
 */
final class JetonInterne {

  private JetonInterne() {}

  /**
   * @param secret secret attendu, vide si non configuré
   * @param authorization en-tête {@code Authorization} reçu
   * @return {@code true} si le secret est configuré et que l'en-tête le porte exactement
   */
  static boolean estValide(final String secret, final String authorization) {
    return !secret.isBlank()
        && MessageDigest.isEqual(
            ("Bearer " + secret).getBytes(StandardCharsets.UTF_8),
            authorization.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * @return la réponse 403 des endpoints internes
   */
  static ResponseEntity<Map<String, Object>> refus() {
    return ResponseEntity.status(403).body(Map.of("erreur", "Accès refusé."));
  }
}
