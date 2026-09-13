/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static fr.strapontin.web.ControllerUtils.erreur;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

/**
 * Contrôleur REST pour la gestion des salles suivies (portage de {@code salles/+page.server.ts}).
 *
 * <p>HTTP pur — délègue toute la logique métier et l'accès aux données à {@link SalleService}.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/salles")
public class SalleController {

  private static final int MAX_SIGNALEMENTS_PAR_HEURE = 3;

  private final SalleService salleService;
  private final EmailService emailService;

  private final Cache<String, Bucket> bucketsSignalement =
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofHours(1).plusMinutes(1))
          .maximumSize(10_000)
          .build();

  /**
   * @param salleService service de gestion des salles
   * @param emailService service d'envoi d'emails
   */
  public SalleController(final SalleService salleService, final EmailService emailService) {
    this.salleService = salleService;
    this.emailService = emailService;
  }

  /**
   * Liste les salles suivies, avec suggestions de recherche si {@code q} est
   * fourni.
   *
   * @param recherche terme de recherche (optionnel, min 2 caractères)
   * @param auth contexte d'authentification
   * @return la liste des salles et les suggestions
   */
  @GetMapping
  public ResponseEntity<ReponseSalles> listerSalles(
      @RequestParam(name = "q", required = false, defaultValue = "") final String recherche,
      final Authentication auth) {
    final String userId = utilisateur(auth).id();
    return ResponseEntity.ok(salleService.lister(userId, recherche));
  }

  /**
   * Ajoute une salle à la liste de suivi.
   *
   * @param requete corps JSON avec le nom et l'ID TM optionnel
   * @param auth contexte d'authentification
   * @return 201 si créée — 409 si doublon, 400 si invalide/cap (via
   *     {@link GlobalExceptionHandler} )
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> ajouterSalle(
      @Valid @RequestBody final RequeteAjouter requete, final Authentication auth) {
    final String userId = utilisateur(auth).id();
    final String nom = requete.nom() == null ? "" : requete.nom().strip();
    if (nom.isEmpty() || nom.length() > 200) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Identifiant de salle invalide."));
    }
    final String tmVenueId = videVersNull(requete.tmVenueId());
    final String osmId = videVersNull(requete.osmId());
    final String wikidataId = videVersNull(requete.wikidataId());

    salleService.ajouter(
        new SalleService.DonneesAjoutSalle(userId, nom, tmVenueId, osmId, wikidataId));
    return ResponseEntity.status(201).body(Map.of("succes", true));
  }

  private static String videVersNull(final String valeur) {
    return valeur == null || valeur.isBlank() ? null : valeur.strip();
  }

  /**
   * Supprime une salle de la liste de suivi.
   *
   * @param salleId UUID stable de la salle
   * @param auth contexte d'authentification
   * @return 204 si supprimée
   */
  @DeleteMapping("/{salleId}")
  public ResponseEntity<Void> supprimerSalle(
      @PathVariable final UUID salleId, final Authentication auth) {
    final String userId = utilisateur(auth).id();
    salleService.supprimer(userId, salleId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Enregistre le signalement d'une salle absente du référentiel.
   *
   * <p>Envoie un email à l'administrateur et un email de confirmation à l'utilisateur. Rate-limité
   * à 3 signalements par heure par IP.
   *
   * @param requete corps JSON avec nom, ville, urlSite, commentaire
   * @param auth contexte d'authentification
   * @param request la requête HTTP (pour rate limiting par IP)
   * @return 204 si transmis, 400 si validation échoue, 429 si rate limit atteint
   */
  @PostMapping("/signaler")
  public ResponseEntity<Map<String, Object>> signalerManquante(
      @Valid @RequestBody final RequeteSignalementSalle requete,
      final Authentication auth,
      final HttpServletRequest request) {
    final UtilisateurDetails utilisateur = utilisateur(auth);

    // @Valid rejette une requête invalide avant l'exécution de cette méthode —
    // le rate-limiting ci-dessous n'est donc jamais consommé par une requête
    // invalide.
    final String nom = requete.nom().strip();
    final String ville = requete.ville() == null ? "" : requete.ville().strip();
    final String urlSite = requete.urlSite() == null ? "" : requete.urlSite().strip();
    final String commentaire = requete.commentaire() == null ? "" : requete.commentaire().strip();

    final String ip = request.getRemoteAddr();
    final Bucket bucket = bucketsSignalement.get(ip, _ -> creerBucketSignalement());
    if (!bucket.tryConsume(1)) {
      return erreur(429, "Trop de signalements. Réessayez dans une heure.");
    }

    emailService.envoyerSignalementSalleManquante(
        new EmailService.SignalementSalleManquante(
            nom, ville, urlSite, commentaire, utilisateur.email()));
    return ResponseEntity.noContent().build();
  }

  /**
   * Réinitialise tous les buckets de signalement (actif uniquement sur le profil {@code dev}).
   *
   * <p>Appelé par le global-setup E2E avant chaque suite de tests pour repartir d'un quota plein.
   *
   * @return 204
   */
  @Profile("dev")
  @DeleteMapping("/rate-limits/signalement")
  public ResponseEntity<Void> reinitialiserBucketsSignalement() {
    bucketsSignalement.invalidateAll();
    return ResponseEntity.noContent().build();
  }

  private Bucket creerBucketSignalement() {
    final Bandwidth limite =
        Bandwidth.builder()
            .capacity(MAX_SIGNALEMENTS_PAR_HEURE)
            .refillIntervally(MAX_SIGNALEMENTS_PAR_HEURE, Duration.ofHours(1))
            .build();
    return Bucket.builder().addLimit(limite).build();
  }

  private static UtilisateurDetails utilisateur(final Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
