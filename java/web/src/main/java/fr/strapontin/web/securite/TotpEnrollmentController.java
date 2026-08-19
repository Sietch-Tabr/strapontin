/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.utilisateur.UserController;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Endpoints REST pour l'enrollment et la désactivation TOTP depuis la SPA Vue.
///
/// <p>HTTP pur — délègue la cryptographie à {@link TotpService} et la persistance à {@link
/// UserController}.
///
/// @since 2.2.0
/// @author Kwaite
@RestController
@RequestMapping("/api/totp")
public class TotpEnrollmentController {

  private static final String SESSION_TOTP_SETUP = "TOTP_SECRET_SETUP";

  private final TotpService totpService;
  private final UserController userController;
  private final LogAdminService logAdminService;

  /// @param totpService service TOTP (génération, vérification)
  /// @param userController contrôleur utilisateur (activation/désactivation TOTP)
  /// @param logAdminService journal d'administration
  public TotpEnrollmentController(
      TotpService totpService, UserController userController, LogAdminService logAdminService) {
    this.totpService = totpService;
    this.userController = userController;
    this.logAdminService = logAdminService;
  }

  /// Génère un nouveau secret TOTP, le stocke en session, et retourne le QR code SVG.
  ///
  /// @param session la session HTTP courante (stocke le secret)
  /// @param auth contexte d'authentification
  /// @return `{otpUri, totpSecret, qrCodeSvg}`
  @GetMapping("/setup")
  public ResponseEntity<Map<String, Object>> setup(HttpSession session, Authentication auth) {
    UtilisateurDetails details = (UtilisateurDetails) auth.getPrincipal();
    // Réutilise le secret déjà en session tant que l'enrollment n'est pas confirmé : éviter de
    // générer un nouveau secret à chaque appel (rechargement de page), ce qui laisserait des clés
    // périmées dans l'application TOTP de l'utilisateur. `confirmer`/`desactiver` purgent la
    // session.
    String secret = (String) session.getAttribute(SESSION_TOTP_SETUP);
    if (secret == null) {
      secret = totpService.genererSecret();
      session.setAttribute(SESSION_TOTP_SETUP, secret);
    }
    String otpUri = totpService.genererUriOtpAuth(secret, details.identifiant());
    String qrCodeSvg = totpService.genererQrCodeSvg(otpUri);

    Map<String, Object> rep = new HashMap<>();
    rep.put("otpUri", otpUri);
    rep.put("totpSecret", secret);
    rep.put("qrCodeSvg", qrCodeSvg);
    return ResponseEntity.ok(rep);
  }

  /// Confirme l'enrollment TOTP en vérifiant le premier code saisi.
  ///
  /// @param corps corps JSON avec `code` (6 chiffres)
  /// @param session la session HTTP courante (lit le secret)
  /// @param auth contexte d'authentification
  /// @return 200 `{succes: true}` ou 400 `{erreur: "..."}`
  @PostMapping("/confirmer")
  public ResponseEntity<Map<String, Object>> confirmer(
      @Valid @RequestBody TotpChallengeRequest corps, HttpSession session, Authentication auth) {
    String secret = (String) session.getAttribute(SESSION_TOTP_SETUP);
    if (secret == null) {
      return erreur(400, "Session expirée. Recommencez la configuration.");
    }

    if (!totpService.verifierCode(secret, corps.code())) {
      return erreur(400, "Code incorrect. Recommencez la configuration.");
    }

    UtilisateurDetails details = (UtilisateurDetails) auth.getPrincipal();
    userController.activerTotp(details.id(), secret);
    session.removeAttribute(SESSION_TOTP_SETUP);
    logAdminService.logAdmin(
        "info",
        "web",
        "TOTP activé",
        Map.of("userId", details.id(), "username", details.identifiant()));

    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Désactive le TOTP après vérification du code courant.
  ///
  /// @param corps corps JSON avec `code` (6 chiffres)
  /// @param auth contexte d'authentification
  /// @return 200 `{succes: true}` ou 400 `{erreur: "..."}`
  @PostMapping("/desactiver")
  public ResponseEntity<Map<String, Object>> desactiver(
      @Valid @RequestBody TotpChallengeRequest corps, Authentication auth) {
    UtilisateurDetails details = (UtilisateurDetails) auth.getPrincipal();
    String secret = userController.lireSecretTotp(details.id());

    if (!totpService.verifierCode(secret, corps.code())) {
      return erreur(400, "Code TOTP incorrect.");
    }

    userController.desactiverTotp(details.id());
    logAdminService.logAdmin(
        "info",
        "web",
        "TOTP désactivé",
        Map.of("userId", details.id(), "username", details.identifiant()));

    return ResponseEntity.ok(Map.of("succes", true));
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
