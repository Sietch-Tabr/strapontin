/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la gestion des salles suivies (portage de {@code salles/+page.server.ts}).
///
/// <p>HTTP pur — délègue toute la logique métier et l'accès aux données à {@link SalleService}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/salles")
public class SalleController {

  private static final int MAX_NOM = 200;
  private static final int MAX_VILLE = 100;
  private static final int MAX_URL_SITE = 500;
  private static final int MAX_COMMENTAIRE = 1000;
  private static final int MAX_SIGNALEMENTS_PAR_HEURE = 3;

  private final SalleService salleService;
  private final EmailService emailService;

  private final Cache<String, Bucket> bucketsSignalement =
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofHours(1).plusMinutes(1))
          .maximumSize(10_000)
          .build();

  /// @param salleService  service de gestion des salles
  /// @param emailService  service d'envoi d'emails
  public SalleController(SalleService salleService, EmailService emailService) {
    this.salleService = salleService;
    this.emailService = emailService;
  }

  /// Liste les salles suivies, avec suggestions de recherche si {@code q} est fourni.
  ///
  /// @param q    terme de recherche (optionnel, min 2 caractères)
  /// @param auth contexte d'authentification
  /// @return la liste des salles et les suggestions
  @GetMapping
  public ResponseEntity<ReponseSalles> listerSalles(
      @RequestParam(required = false, defaultValue = "") String q, Authentication auth) {
    String userId = utilisateur(auth).id();
    return ResponseEntity.ok(salleService.lister(userId, q));
  }

  /// Ajoute une salle à la liste de suivi.
  ///
  /// @param requete corps JSON avec le nom et l'ID TM optionnel
  /// @param auth    contexte d'authentification
  /// @return 201 si créée — 409 si doublon, 400 si invalide/cap (via {@link
  // GlobalExceptionHandler})
  @PostMapping
  public ResponseEntity<Map<String, Object>> ajouterSalle(
      @RequestBody RequeteAjouter requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    String nom = requete.nom() == null ? "" : requete.nom().strip();
    if (nom.isEmpty() || nom.length() > 200) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Identifiant de salle invalide."));
    }
    String tmVenueId =
        requete.tmVenueId() == null || requete.tmVenueId().isBlank()
            ? null
            : requete.tmVenueId().strip();

    salleService.ajouter(userId, nom, tmVenueId);
    return ResponseEntity.status(201).body(Map.of("succes", true));
  }

  /// Supprime une salle de la liste de suivi.
  ///
  /// @param salleUuid UUID stable de la salle
  /// @param auth      contexte d'authentification
  /// @return 204 si supprimée
  @DeleteMapping("/{salleUuid}")
  public ResponseEntity<Void> supprimerSalle(@PathVariable UUID salleUuid, Authentication auth) {
    String userId = utilisateur(auth).id();
    salleService.supprimer(userId, salleUuid);
    return ResponseEntity.noContent().build();
  }

  /// Enregistre le signalement d'une salle absente du référentiel.
  ///
  /// <p>Envoie un email à l'administrateur et un email de confirmation à l'utilisateur.
  /// Rate-limité à 3 signalements par heure par IP.
  ///
  /// @param requete  corps JSON avec nom, ville, urlSite, commentaire
  /// @param auth     contexte d'authentification
  /// @param request  la requête HTTP (pour rate limiting par IP)
  /// @return 204 si transmis, 400 si validation échoue, 429 si rate limit atteint
  @PostMapping("/signaler")
  public ResponseEntity<Map<String, Object>> signalerManquante(
      @RequestBody RequeteSignalementSalle requete,
      Authentication auth,
      HttpServletRequest request) {
    UtilisateurDetails utilisateur = utilisateur(auth);

    // Validation avant rate-limiting : une requête invalide ne consomme pas de quota
    String nom = requete.nom() == null ? "" : requete.nom().strip();
    if (nom.isEmpty()) {
      return erreur(400, "Le nom de la salle est requis.");
    }
    if (nom.length() > MAX_NOM) {
      return erreur(400, "Le nom ne peut pas dépasser " + MAX_NOM + " caractères.");
    }

    String ville = requete.ville() == null ? "" : requete.ville().strip();
    if (ville.length() > MAX_VILLE) {
      return erreur(400, "La ville ne peut pas dépasser " + MAX_VILLE + " caractères.");
    }

    String urlSite = requete.urlSite() == null ? "" : requete.urlSite().strip();
    if (urlSite.length() > MAX_URL_SITE) {
      return erreur(400, "L'URL ne peut pas dépasser " + MAX_URL_SITE + " caractères.");
    }

    String commentaire = requete.commentaire() == null ? "" : requete.commentaire().strip();
    if (commentaire.length() > MAX_COMMENTAIRE) {
      return erreur(400, "Le commentaire ne peut pas dépasser " + MAX_COMMENTAIRE + " caractères.");
    }

    String ip = request.getRemoteAddr();
    Bucket bucket = bucketsSignalement.get(ip, _ -> creerBucketSignalement());
    if (!bucket.tryConsume(1)) {
      return erreur(429, "Trop de signalements. Réessayez dans une heure.");
    }

    emailService.envoyerSignalementSalleManquante(
        nom, ville, urlSite, commentaire, utilisateur.email());
    return ResponseEntity.noContent().build();
  }

  /// Réinitialise tous les buckets de signalement (actif uniquement sur le profil {@code dev}).
  ///
  /// <p>Appelé par le global-setup E2E avant chaque suite de tests pour repartir d'un quota plein.
  ///
  /// @return 204
  @Profile("dev")
  @DeleteMapping("/rate-limits/signalement")
  public ResponseEntity<Void> reinitialiserBucketsSignalement() {
    bucketsSignalement.invalidateAll();
    return ResponseEntity.noContent().build();
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    return ResponseEntity.status(status).body(Map.of("erreur", message));
  }

  private Bucket creerBucketSignalement() {
    Bandwidth limite =
        Bandwidth.builder()
            .capacity(MAX_SIGNALEMENTS_PAR_HEURE)
            .refillIntervally(MAX_SIGNALEMENTS_PAR_HEURE, Duration.ofHours(1))
            .build();
    return Bucket.builder().addLimit(limite).build();
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
