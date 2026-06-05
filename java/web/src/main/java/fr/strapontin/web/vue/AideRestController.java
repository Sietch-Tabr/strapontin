/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la page d'aide (configuration + signalements).
///
/// @since 2.2.0
/// @author Kwaite
@RestController
@RequestMapping("/api/aide")
public class AideRestController {

  private static final String EMAIL_CONTACT_DEFAUT = "admin@strapontin.kwaite.fr";
  private static final int MAX_LONGUEUR_MESSAGE = 2000;
  private static final int MAX_SIGNALEMENTS_PAR_HEURE = 3;

  private final EmailService emailService;
  private final ConfigService configService;

  private final Cache<String, Bucket> bucketsSignalement =
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofHours(1).plusMinutes(1))
          .maximumSize(10_000)
          .build();

  /// @param emailService  service d'envoi d'emails
  /// @param configService service de configuration applicative
  public AideRestController(EmailService emailService, ConfigService configService) {
    this.emailService = emailService;
    this.configService = configService;
  }

  /// Retourne la configuration nécessaire à la page d'aide.
  ///
  /// @return resendDisponible et contactEmail
  @GetMapping("/config")
  public ResponseEntity<Map<String, Object>> config() {
    Map<String, Object> corps = new HashMap<>();
    corps.put("resendDisponible", emailService.estDisponible());
    corps.put(
        "contactEmail", configService.lireConfig("CONTACT_EMAIL").orElse(EMAIL_CONTACT_DEFAUT));
    return ResponseEntity.ok(corps);
  }

  /// Envoie un signalement à l'adresse de contact.
  ///
  /// @param requete  corps JSON avec type et message
  /// @param auth     contexte d'authentification
  /// @param request  la requête HTTP (pour rate limiting par IP)
  /// @return 204 si envoyé, 400 si validation échoue, 429 si rate limit atteint
  @PostMapping("/signalement")
  public ResponseEntity<Map<String, Object>> signaler(
      @RequestBody RequeteSignalement requete, Authentication auth, HttpServletRequest request) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails utilisateur)) {
      return erreur(401, "Non authentifié.");
    }

    String ip = request.getRemoteAddr();
    Bucket bucket = bucketsSignalement.get(ip, _ -> creerBucketSignalement());
    if (!bucket.tryConsume(1)) {
      return erreur(429, "Trop de signalements. Réessayez dans une heure.");
    }

    String msg = requete.message() == null ? "" : requete.message().strip();
    if (msg.isEmpty()) {
      return erreur(400, "Le message est requis.");
    }
    if (msg.length() > MAX_LONGUEUR_MESSAGE) {
      return erreur(
          400, "Le message ne peut pas dépasser " + MAX_LONGUEUR_MESSAGE + " caractères.");
    }

    String typeValide =
        Set.of("bug", "suggestion", "question").contains(requete.type())
            ? requete.type()
            : "question";
    emailService.envoyerSignalement(typeValide, msg, utilisateur.email(), utilisateur.nom());
    return ResponseEntity.noContent().build();
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }

  private Bucket creerBucketSignalement() {
    Bandwidth limite =
        Bandwidth.builder()
            .capacity(MAX_SIGNALEMENTS_PAR_HEURE)
            .refillIntervally(MAX_SIGNALEMENTS_PAR_HEURE, Duration.ofHours(1))
            .build();
    return Bucket.builder().addLimit(limite).build();
  }
}
