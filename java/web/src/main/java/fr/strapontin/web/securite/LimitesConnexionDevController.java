/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Remise à zéro des limites de connexion, réservée au profil {@code dev} et
 * aux administrateurs : appelée par le global-setup E2E pour que les runs
 * rapprochés ne s'épuisent pas mutuellement le quota de création de compte.
 * @since 3.1.4
 * @author Kwaite
 */
@Profile("dev")
@RestController
@RequestMapping("/api/admin/rate-limits")
public class LimitesConnexionDevController {

  private final RateLimitingLoginFilter filtre;

  /**
   * @param filtre filtre qui porte les limiteurs
   */
  public LimitesConnexionDevController(final RateLimitingLoginFilter filtre) {
    this.filtre = filtre;
  }

  /**
   * @return 204
   */
  @DeleteMapping("/connexion")
  public ResponseEntity<Void> reinitialiserConnexion() {
    filtre.reinitialiserLimites();
    return ResponseEntity.noContent().build();
  }
}
