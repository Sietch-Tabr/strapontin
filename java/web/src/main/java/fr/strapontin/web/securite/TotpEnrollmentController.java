/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.utilisateur.UserController;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST pour l'enrollment et la désactivation TOTP
 * depuis la SPA Vue — HTTP pur, délègue à {@link TotpService}
 * et {@link UserController}.
 * @since 2.2.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/totp")
public class TotpEnrollmentController {

  private static final String SESSION_TOTP_SETUP = "TOTP_SECRET_SETUP";
  private static final int MAX_ECHECS_DESACTIVATION = 5;
  private static final Duration FENETRE_ECHECS_DESACTIVATION = Duration.ofMinutes(15);

  private final TotpService totpService;
  private final UserController userController;
  private final LogAdminService logAdminService;
  // Une session déjà ouverte (volée, par exemple) ne doit pas pouvoir deviner le
  // code TOTP.
  private final LimiteurDebit echecsDesactivation =
      new LimiteurDebit(
          MAX_ECHECS_DESACTIVATION,
          FENETRE_ECHECS_DESACTIVATION,
          FENETRE_ECHECS_DESACTIVATION.plusMinutes(5),
          10_000);

  /**
   * @param totpService service TOTP (génération, vérification)
   * @param userController contrôleur utilisateur (activation/désactivation
   *     TOTP)
   * @param logAdminService journal d'administration
   */
  public TotpEnrollmentController(
      final TotpService totpService,
      final UserController userController,
      final LogAdminService logAdminService) {
    this.totpService = totpService;
    this.userController = userController;
    this.logAdminService = logAdminService;
  }

  /**
   * Génère un nouveau secret TOTP, le stocke en session, et retourne le QR code
   * SVG.
   *
   * @param session la session HTTP courante (stocke le secret)
   * @param auth contexte d'authentification
   * @return {@code {otpUri, totpSecret, qrCodeSvg}}
   */
  @GetMapping("/setup")
  public ResponseEntity<Map<String, Object>> setup(
      final HttpSession session, final Authentication auth) {
    final UtilisateurDetails details = (UtilisateurDetails) auth.getPrincipal();
    // Réutilise le secret déjà en session tant que l'enrollment n'est pas
    // confirmé : éviter de générer un nouveau secret à chaque appel
    // (rechargement de page), ce qui laisserait des clés périmées dans
    // l'application TOTP de l'utilisateur. `confirmer`/`desactiver` purgent la
    // session.
    String secret = (String) session.getAttribute(SESSION_TOTP_SETUP);
    if (secret == null) {
      secret = totpService.genererSecret();
      session.setAttribute(SESSION_TOTP_SETUP, secret);
    }
    final String otpUri = totpService.genererUriOtpAuth(secret, details.identifiant());
    final String qrCodeSvg = totpService.genererQrCodeSvg(otpUri);

    final Map<String, Object> rep = new HashMap<>();
    rep.put("otpUri", otpUri);
    rep.put("totpSecret", secret);
    rep.put("qrCodeSvg", qrCodeSvg);
    return ResponseEntity.ok(rep);
  }

  /**
   * Confirme l'enrollment TOTP en vérifiant le premier code saisi.
   *
   * @param corps corps JSON avec {@code code} (6 chiffres)
   * @param session la session HTTP courante (lit le secret)
   * @param auth contexte d'authentification
   * @return 200 {@code {succes: true}} ou 400 {@code {erreur: "..."}}
   */
  @PostMapping("/confirmer")
  public ResponseEntity<Map<String, Object>> confirmer(
      @Valid @RequestBody final TotpChallengeRequest corps,
      final HttpSession session,
      final Authentication auth) {
    final String secret = (String) session.getAttribute(SESSION_TOTP_SETUP);
    if (secret == null) {
      return erreur(400, "Session expirée. Recommencez la configuration.");
    }

    if (!totpService.verifierCode(secret, corps.code())) {
      return erreur(400, "Code incorrect. Recommencez la configuration.");
    }

    final UtilisateurDetails details = (UtilisateurDetails) auth.getPrincipal();
    userController.activerTotp(details.id(), secret);
    session.removeAttribute(SESSION_TOTP_SETUP);
    logAdminService.logAdmin(
        "info",
        "web",
        "TOTP activé",
        Map.of("userId", details.id(), "username", details.identifiant()));

    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Désactive le TOTP après vérification du code courant.
   *
   * @param corps corps JSON avec {@code code} (6 chiffres)
   * @param auth contexte d'authentification
   * @return 200 {@code {succes: true}}, 400 {@code {erreur: "..."}}, ou 429
   *     après 5 codes incorrects en 15 minutes
   */
  @PostMapping("/desactiver")
  public ResponseEntity<Map<String, Object>> desactiver(
      @Valid @RequestBody final TotpChallengeRequest corps, final Authentication auth) {
    final UtilisateurDetails details = (UtilisateurDetails) auth.getPrincipal();
    if (echecsDesactivation.estEpuise(details.id())) {
      return erreur(429, "Trop de tentatives. Réessayez dans quelques minutes.");
    }
    final String secret = userController.lireSecretTotp(details.id());

    if (!totpService.verifierCode(secret, corps.code())) {
      if (echecsDesactivation.enregistrer(details.id())) {
        logAdminService.logAdmin(
            "warn",
            "auth",
            "Désactivation du TOTP bloquée après échecs répétés : " + details.identifiant(),
            Map.of("userId", details.id()));
      }
      return erreur(400, "Code TOTP incorrect.");
    }
    echecsDesactivation.oublier(details.id());

    userController.desactiverTotp(details.id());
    logAdminService.logAdmin(
        "info",
        "web",
        "TOTP désactivé",
        Map.of("userId", details.id(), "username", details.identifiant()));

    return ResponseEntity.ok(Map.of("succes", true));
  }
}
