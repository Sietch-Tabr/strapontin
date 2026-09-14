/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import fr.strapontin.web.concert.FiltreConcerts;
import fr.strapontin.web.securite.UtilisateurDetails;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour la découverte de concerts par département.
 *
 * <p>HTTP pur — délègue toute la logique à {@link DecouverteService}.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/decouverte")
public class DecouverteController {

  private final DecouverteService decouverteService;

  /**
   * {@code fetcher()} déclenche le même cycle global que le bouton admin
   * « Relance manuelle globale » — un seul bucket partagé (pas par
   * utilisateur), pour éviter qu'un double-clic/script consomme le quota
   * TM. Défense en profondeur (endpoint déjà {@code ROLE_ADMIN}).
   */
  private final Bucket bucketFetcher =
      Bucket.builder()
          .addLimit(
              Bandwidth.builder().capacity(1).refillIntervally(1, Duration.ofMinutes(5)).build())
          .build();

  /**
   * @param decouverteService service de découverte
   */
  public DecouverteController(final DecouverteService decouverteService) {
    this.decouverteService = decouverteService;
  }

  /**
   * Retourne les concerts filtrés par département.
   *
   * @param filtre critères de filtrage (liés depuis les paramètres de requête)
   * @param auth contexte d'authentification
   * @return les concerts et les filtres disponibles
   */
  @GetMapping
  public ResponseEntity<ReponseDecouverte> decouvrir(
      @ModelAttribute final FiltreConcerts filtre, final Authentication auth) {
    final String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    return ResponseEntity.ok(decouverteService.decouvrir(userId, filtre));
  }

  /**
   * Déclenche un fetch global ({@code requete.depts} n'est pas exploité, le
   * worker refetch systématiquement tout). Rate-limité à 1/5 min sur
   * l'ensemble des utilisateurs pour éviter une consommation abusive du
   * quota TM.
   * @param requete corps JSON avec la liste de départements (non exploitée)
   * @return 200 si déclenché, 429 si trop de relances, 503 si worker injoignable
   */
  @PostMapping("/fetcher")
  public ResponseEntity<Map<String, Object>> fetcher(
      @Valid @RequestBody final RequeteFetch requete) {
    if (!bucketFetcher.tryConsume(1)) {
      return ResponseEntity.status(429)
          .body(Map.of("erreur", "Trop de relances récentes. Réessayez dans quelques minutes."));
    }
    return decouverteService.fetcher();
  }
}
