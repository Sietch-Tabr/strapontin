/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import fr.strapontin.web.concert.FiltreConcerts;
import fr.strapontin.web.securite.UtilisateurDetails;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la découverte de concerts par département.
///
/// <p>HTTP pur — délègue toute la logique à {@link DecouverteService}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/decouverte")
public class DecouverteController {

  private final DecouverteService decouverteService;

  /// {@code fetcher()} déclenche le même cycle TM/Eventim global que le bouton admin « Relance
  /// manuelle globale » — un seul bucket partagé par tous les appelants, pas un bucket par
  /// utilisateur, pour empêcher qu'une succession rapide de déclenchements (double-clic, script)
  /// ne consomme inutilement le quota TM. L'endpoint est déjà restreint à {@code ROLE_ADMIN}
  /// depuis la 2.5.0 ({@code SecurityConfiguration}) — ce rate-limit est une défense en
  /// profondeur, pas la correction d'un accès non-admin (la description initiale du ticket
  /// affirmait à tort que tout utilisateur authentifié pouvait l'appeler).
  private final Bucket bucketFetcher =
      Bucket.builder()
          .addLimit(
              Bandwidth.builder().capacity(1).refillIntervally(1, Duration.ofMinutes(5)).build())
          .build();

  /// @param decouverteService service de découverte
  public DecouverteController(DecouverteService decouverteService) {
    this.decouverteService = decouverteService;
  }

  /// Retourne les concerts filtrés par département.
  ///
  /// @param auth contexte d'authentification
  /// @return les concerts et les filtres disponibles
  @GetMapping
  public ResponseEntity<ReponseDecouverte> decouvrir(
      @RequestParam(name = "dept", required = false) List<String> depts,
      @RequestParam(required = false, defaultValue = "") String q,
      @RequestParam(required = false, defaultValue = "") String mois,
      @RequestParam(required = false, defaultValue = "") String genre,
      @RequestParam(required = false, defaultValue = "") String classification,
      @RequestParam(required = false, defaultValue = "date") String tri,
      @RequestParam(required = false, defaultValue = "0") int page,
      Authentication auth) {
    String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    FiltreConcerts filtre =
        new FiltreConcerts(
            q, mois, genre, classification, tri, page, false, false, null, null, depts);
    return ResponseEntity.ok(decouverteService.decouvrir(userId, filtre));
  }

  /// Déclenche un fetch global ({@code requete.depts} n'est pas exploité, le worker
  /// refetch systématiquement tous les départements et artistes suivis — même comportement que
  /// le bouton admin « Relance manuelle globale »). Rate-limité à 1 déclenchement / 5 min sur
  /// l'ensemble des utilisateurs pour éviter une consommation abusive du quota TM.
  ///
  /// @param requete corps JSON avec la liste de départements (actuellement non exploitée)
  /// @return 200 si déclenché, 429 si trop de relances récentes, 503 si le worker est inaccessible
  @PostMapping("/fetcher")
  public ResponseEntity<Map<String, Object>> fetcher(@RequestBody RequeteFetch requete) {
    if (!bucketFetcher.tryConsume(1)) {
      return ResponseEntity.status(429)
          .body(Map.of("erreur", "Trop de relances récentes. Réessayez dans quelques minutes."));
    }
    return decouverteService.fetcher();
  }
}
