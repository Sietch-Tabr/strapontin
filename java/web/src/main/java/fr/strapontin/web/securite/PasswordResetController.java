/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de réinitialisation de mot de passe et d'activation
 * de compte invité — HTTP pur, délègue à {@link AuthService}.
 * @since 2.9.1
 * @author Kwaite
 */
@RestController
public class PasswordResetController {

  private static final Logger LOG = LoggerFactory.getLogger(PasswordResetController.class);

  private static final int MAX_DEMANDES_PAR_ADRESSE = 3;

  private final AuthenticationManager authenticationManager;
  private final AuthService authService;
  private final ConfigService configService;
  private final LogAdminService logAdminService;
  // Clé = adresse saisie, qu'elle corresponde ou non à un compte : le refus ne
  // révèle rien.
  private final LimiteurDebit demandesParAdresse =
      new LimiteurDebit(MAX_DEMANDES_PAR_ADRESSE, Duration.ofHours(1), Duration.ofHours(2), 10_000);

  /**
   * @param authenticationManager ouverture de session lors d'une activation
   * @param authService service de réinitialisation de mot de passe
   * @param configService service de configuration pour lire PUBLIC_BASE_URL
   * @param logAdminService audit des tentatives bloquées (URL absente)
   */
  public PasswordResetController(
      final AuthenticationManager authenticationManager,
      final AuthService authService,
      final ConfigService configService,
      final LogAdminService logAdminService) {
    this.authenticationManager = authenticationManager;
    this.authService = authService;
    this.configService = configService;
    this.logAdminService = logAdminService;
  }

  /**
   * Déclenche l'envoi d'un email de réinitialisation de mot de passe.
   * Retourne toujours 200 pour éviter l'énumération de comptes ; au-delà de 3
   * demandes par heure pour une même adresse, la demande est ignorée.
   * @param req le corps contenant l'adresse email
   * @return 200 dans tous les cas
   */
  @PostMapping("/api/auth/forgot-password")
  public ResponseEntity<Map<String, Object>> motDePasseOublie(
      @RequestBody final ForgotPasswordRequest req) {
    if (req.email() != null && !req.email().isBlank()) {
      resoudreBaseUrl()
          .ifPresentOrElse(
              baseUrl -> {
                final String email = req.email().strip().toLowerCase(Locale.ROOT);
                if (demandesParAdresse.autoriser(email)) {
                  authService.motDePasseOublie(email, baseUrl);
                }
              },
              () ->
                  logAdminService.logAdmin(
                      "error",
                      "auth",
                      "Réinitialisation de mot de passe bloquée — PUBLIC_BASE_URL non configurée"));
    }
    // Réponse identique que l'email existe ou non, et que PUBLIC_BASE_URL soit
    // configurée ou non — évite toute énumération de compte ou fuite de
    // configuration au client.
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Retourne le type d'un token de vérification sans le consommer.
   *
   * <p>Retourne toujours 200 — {@code "reset"} si absent/invalide (opacité).
   *
   * @param token token brut (non hashé)
   * @return {@code {type: "invite"}} ou {@code {type: "reset"}}
   */
  @GetMapping("/api/auth/type-token")
  public ResponseEntity<Map<String, Object>> typeToken(@RequestParam final String token) {
    final boolean estInvite = authService.estTokenInvite(token);
    return ResponseEntity.ok(Map.of("type", estInvite ? "invite" : "reset"));
  }

  /**
   * Réinitialise le mot de passe via un token de vérification
   * (détail : CLAUDE.md, Authentification).
   * @param req token, mot de passe, identifiant optionnel
   * @param httpRequest pour créer la session (activation invitation)
   * @return 200 si réinitialisé — 400 si invalide
   */
  @PostMapping("/api/auth/reset-password")
  public ResponseEntity<?> reinitialiserMotDePasse(
      @Valid @RequestBody final ResetPasswordRequest req, final HttpServletRequest httpRequest) {
    if (req.identifiant() != null && !req.identifiant().isBlank()) {
      final String ident = RegleIdentifiant.valider(req.identifiant());
      final String identFinal =
          authService.activerCompteInvite(req.token(), ident, req.motDePasse());

      final UsernamePasswordAuthenticationToken tokenNonAuthentifie =
          UsernamePasswordAuthenticationToken.unauthenticated(identFinal, req.motDePasse());
      tokenNonAuthentifie.setDetails(new WebAuthenticationDetails(httpRequest));
      final Authentication auth = authenticationManager.authenticate(tokenNonAuthentifie);

      final UtilisateurDetails details =
          EtablisseurSession.ouvrir(
                  auth, httpRequest, "Principal inattendu après activation invitation")
              .details();
      LOG.debug("Activation compte invitation, connexion : {}", details.identifiant());
      return ResponseEntity.ok(
          new UtilisateurDto(
              details.id(), details.identifiant(), details.email(), details.role(), false, false));
    }

    authService.reinitialiserMotDePasse(req.token(), req.motDePasse());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Résout l'URL de base publique pour un lien envoyé par email
   * (détail : CLAUDE.md, Sécurité admin/sessions — Host Header
   * Injection).
   * @return l'URL de base configurée, ou vide si absente
   */
  private Optional<String> resoudreBaseUrl() {
    return configService.lireConfig("PUBLIC_BASE_URL").filter(s -> !s.isBlank());
  }
}
