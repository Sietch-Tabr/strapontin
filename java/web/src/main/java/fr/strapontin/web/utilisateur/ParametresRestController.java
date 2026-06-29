/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Endpoints REST pour les paramètres utilisateur non couverts par les contrôleurs existants.
///
/// <p>Gère le changement d'email (flux de confirmation), les compteurs artistes/salles
/// et la confirmation du changement d'email depuis le lien envoyé par email.
///
/// @since 2.2.0
/// @author Kwaite
@RestController
@RequestMapping("/api/parametres")
public class ParametresRestController {

  private static final Logger log = LoggerFactory.getLogger(ParametresRestController.class);
  private static final Pattern FORMAT_EMAIL =
      Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

  private final ParametresService parametresService;
  private final ConfigService configService;

  /// @param parametresService service métier des paramètres
  /// @param configService     service de configuration pour lire PUBLIC_BASE_URL
  public ParametresRestController(
      ParametresService parametresService, ConfigService configService) {
    this.parametresService = parametresService;
    this.configService = configService;
  }

  /// Retourne les compteurs d'artistes et de salles pour l'utilisateur courant.
  ///
  /// @param auth contexte d'authentification
  /// @return `{nbArtistes, nbSalles}`
  @GetMapping("/stats")
  public ResponseEntity<Map<String, Object>> stats(Authentication auth) {
    return ResponseEntity.ok(parametresService.stats(utilisateur(auth).id()));
  }

  /// Déclenche un flux de confirmation pour un changement d'adresse email.
  ///
  /// <p>Envoie un email de confirmation à la nouvelle adresse. L'email est mis à jour
  /// uniquement après validation du lien (24 h) via {@link #confirmerEmail}.
  ///
  /// @param corps   corps JSON avec `email` (nouvelle adresse)
  /// @param auth    contexte d'authentification
  /// @param request la requête HTTP (pour construire le lien de confirmation)
  /// @return 200 `{succes: true}` ou 400/503 `{erreur: "..."}`
  @PostMapping("/email-change")
  public ResponseEntity<Map<String, Object>> emailChange(
      @RequestBody Map<String, String> corps, Authentication auth, HttpServletRequest request) {
    UtilisateurDetails details = utilisateur(auth);
    String emailBrut = corps.getOrDefault("email", "");

    if (emailBrut.isBlank()) {
      return erreur(400, "L'adresse email est requise.");
    }
    String emailNorm = emailBrut.strip().toLowerCase();
    if (!FORMAT_EMAIL.matcher(emailNorm).matches() || emailNorm.length() > 254) {
      return erreur(400, "Format d'email invalide.");
    }
    if (emailNorm.equalsIgnoreCase(details.email())) {
      return erreur(400, "Cette adresse est déjà votre adresse email actuelle.");
    }
    if (parametresService.emailDejaUtilise(emailNorm, details.id())) {
      return erreur(400, "Cette adresse email est déjà utilisée.");
    }
    if (!parametresService.emailDisponible()) {
      return erreur(
          503, "L'envoi d'email n'est pas configuré. Impossible de confirmer le changement.");
    }

    parametresService.demanderChangementEmail(details, emailNorm, baseUrl(request));
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Confirme le changement d'email via le token envoyé par email.
  ///
  /// <p>L'utilisateur doit être connecté (session active). En cas de succès,
  /// redirige vers `/parametres?succes=email-confirme#compte`.
  ///
  /// @param token     le token de confirmation (paramètre URL)
  /// @param auth      contexte d'authentification
  /// @return redirect 302 vers `/parametres?succes=email-confirme#compte` ou une page d'erreur
  @GetMapping("/confirmer-email")
  public ResponseEntity<Void> confirmerEmail(
      @RequestParam(required = false) String token, Authentication auth) {
    String userIdAuthentifie = utilisateur(auth).id();
    ParametresService.ResultatConfirmation resultat =
        parametresService.confirmerEmail(token, userIdAuthentifie);
    String redirection =
        switch (resultat.statut()) {
          case OK -> "/parametres?succes=email-confirme#compte";
          case LIEN_INVALIDE -> "/parametres?erreur=lien-invalide#compte";
          case LIEN_EXPIRE -> "/parametres?erreur=lien-expire#compte";
          case MAUVAIS_UTILISATEUR -> "/parametres?erreur=lien-invalide#compte";
          case AUCUN_CHANGEMENT -> "/parametres?erreur=aucun-changement-email#compte";
          case EMAIL_DEJA_UTILISE -> "/parametres?erreur=email-deja-utilise#compte";
        };
    return ResponseEntity.status(302).location(URI.create(redirection)).build();
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }

  private String baseUrl(HttpServletRequest request) {
    return configService
        .lireConfig("PUBLIC_BASE_URL")
        .filter(s -> !s.isBlank())
        .orElseGet(
            () -> {
              log.warn(
                  "PUBLIC_BASE_URL absente — URL construite depuis la requête (risque Host Header"
                      + " Injection si derrière un proxy)");
              String scheme = request.getScheme();
              String host = request.getServerName();
              int port = request.getServerPort();
              boolean portDefaut =
                  ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
              return scheme
                  + "://"
                  + host
                  + (portDefaut ? "" : ":" + port)
                  + request.getContextPath();
            });
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
