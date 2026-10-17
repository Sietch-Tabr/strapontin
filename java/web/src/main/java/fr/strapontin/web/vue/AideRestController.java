/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.LimiteurDebit;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

/**
 * Contrôleur REST pour la page d'aide (configuration + signalements).
 *
 * @since 2.2.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/aide")
public class AideRestController {

  private static final int MAX_SIGNALEMENTS_PAR_HEURE = 3;

  private final EmailService emailService;
  private final ConfigService configService;

  private final LimiteurDebit limiteurSignalement =
      new LimiteurDebit(
          MAX_SIGNALEMENTS_PAR_HEURE,
          Duration.ofHours(1),
          Duration.ofHours(1).plusMinutes(1),
          10_000);

  /**
   * @param emailService service d'envoi d'emails
   * @param configService service de configuration applicative
   */
  public AideRestController(final EmailService emailService, final ConfigService configService) {
    this.emailService = emailService;
    this.configService = configService;
  }

  /**
   * Retourne la configuration nécessaire à la page d'aide.
   *
   * @return resendDisponible, contactEmail et signalLien
   */
  @GetMapping("/config")
  public ResponseEntity<Map<String, Object>> config() {
    final Map<String, Object> corps = new HashMap<>();
    corps.put("resendDisponible", emailService.estDisponible());
    corps.put("contactEmail", configService.lireConfig("CONTACT_EMAIL").orElse(""));
    corps.put("signalLien", configService.lireConfig("SIGNAL_LIEN").orElse(""));
    return ResponseEntity.ok(corps);
  }

  /**
   * Envoie un signalement à l'adresse de contact.
   *
   * @param requete corps JSON avec type et message
   * @param auth contexte d'authentification
   * @param request la requête HTTP (pour rate limiting par IP)
   * @return 204 si envoyé, 400 si validation échoue, 429 si rate limit atteint
   */
  @PostMapping("/signalement")
  public ResponseEntity<Map<String, Object>> signaler(
      @Valid @RequestBody final RequeteSignalement requete,
      final Authentication auth,
      final HttpServletRequest request) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails utilisateur)) {
      return erreur(401, "Non authentifié.");
    }

    final String ip = request.getRemoteAddr();
    if (!limiteurSignalement.autoriser(ip)) {
      return erreur(429, "Trop de signalements. Réessayez dans une heure.");
    }

    final String msg = requete.message().strip();
    final String typeValide =
        Set.of("bug", "suggestion", "question").contains(requete.type())
            ? requete.type()
            : "question";
    emailService.envoyerSignalement(typeValide, msg, utilisateur.email());
    return ResponseEntity.noContent().build();
  }
}
