/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints d'authentification de l'API REST.
 *
 * <p>HTTP pur — délègue les flux reset MDP à {@link AuthService} et la vérification TOTP (code +
 * compteur d'échecs + verrouillage) à {@link TotpChallengeService}. Gère le login, logout,
 * challenge TOTP, récupération de l'utilisateur courant et la réinitialisation de mot de passe.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
public class AuthController {

  private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);
  private static final String CLE_ERREUR = "erreur";

  private final AuthenticationManager authenticationManager;
  private final TotpChallengeService totpChallengeService;

  /**
   * @param authenticationManager gestionnaire d'authentification Spring
   *     Security
   * @param totpChallengeService vérification du challenge TOTP (code + compteur
   *     d'échecs)
   */
  public AuthController(
      final AuthenticationManager authenticationManager,
      final TotpChallengeService totpChallengeService) {
    this.authenticationManager = authenticationManager;
    this.totpChallengeService = totpChallengeService;
  }

  /**
   * Authentifie un utilisateur et crée une session persistante.
   *
   * @param req les credentials ({@code identifiant} + {@code motDePasse})
   * @param httpRequest la requête HTTP (pour créer la session)
   * @return l'utilisateur authentifié ou 401 si les credentials sont invalides
   */
  @PostMapping("/api/auth/login")
  public ResponseEntity<UtilisateurDto> login(
      @Valid @RequestBody final LoginRequest req, final HttpServletRequest httpRequest) {

    final UsernamePasswordAuthenticationToken tokenNonAuthentifie =
        UsernamePasswordAuthenticationToken.unauthenticated(req.identifiant(), req.motDePasse());
    tokenNonAuthentifie.setDetails(new WebAuthenticationDetails(httpRequest));
    final Authentication auth = authenticationManager.authenticate(tokenNonAuthentifie);

    final EtablisseurSession.SessionOuverte sessionOuverte =
        EtablisseurSession.ouvrir(auth, httpRequest, "Principal inattendu après authentification");
    final UtilisateurDetails details = sessionOuverte.details();
    final boolean totpRequis = details.twoFactorActif();
    if (totpRequis) {
      sessionOuverte
          .session()
          .setAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE, Boolean.TRUE);
    }
    LOG.debug("Connexion réussie : {}", details.identifiant());
    return ResponseEntity.ok(
        new UtilisateurDto(
            details.id(),
            details.identifiant(),
            details.email(),
            details.role(),
            details.doitChangerCredentials(),
            totpRequis));
  }

  /**
   * Invalide la session courante, efface le cookie SESSION et déconnecte
   * l'utilisateur.
   *
   * @param httpRequest la requête HTTP (pour invalider la session)
   * @param httpResponse la réponse HTTP (pour supprimer le cookie SESSION)
   * @return 204 No Content
   */
  @PostMapping("/api/auth/logout")
  public ResponseEntity<Void> logout(
      final HttpServletRequest httpRequest, final HttpServletResponse httpResponse) {
    final HttpSession session = httpRequest.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    final Cookie cookie = new Cookie("SESSION", "");
    cookie.setMaxAge(0);
    cookie.setPath("/");
    cookie.setHttpOnly(true);
    cookie.setSecure(true);
    cookie.setAttribute("SameSite", "Lax");
    httpResponse.addCookie(cookie);
    return ResponseEntity.noContent().build();
  }

  /**
   * Vérifie le code TOTP soumis pendant le challenge post-login.
   *
   * <p>Le compteur d'échecs et la logique de verrouillage sont délégués à {@link
   * TotpChallengeService}. Un admin peut débloquer via {@code POST
   * /api/admin/utilisateurs/{id}/reset-totp}.
   *
   * @param req le corps contenant le code à 6 chiffres
   * @param httpRequest la requête HTTP (pour lire et modifier la session)
   * @param auth le contexte d'authentification Spring Security
   * @return 200 si valide — 422 si code incorrect — 409 si pas de challenge en attente — 401 si
   *     trop d'échecs ou compte verrouillé
   */
  @PostMapping("/api/auth/totp")
  public ResponseEntity<Map<String, Object>> verifierTotp(
      @Valid @RequestBody final TotpChallengeRequest req,
      final HttpServletRequest httpRequest,
      final Authentication auth) {
    final HttpSession session = httpRequest.getSession(false);
    if (!challengeTotpEnAttente(session)) {
      return ResponseEntity.status(409).body(Map.of(CLE_ERREUR, "Aucun challenge TOTP en attente"));
    }

    final UtilisateurDetails principal = principalAuthentifie(auth);
    if (principal == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of(CLE_ERREUR, "Authentification requise"));
    }

    final ResultatTotp resultat =
        totpChallengeService.verifier(
            new TotpChallengeService.ChallengeTotp(
                principal.id(), principal.identifiant(), req.code(), httpRequest.getRemoteAddr()));

    return construireReponseTotp(resultat, session, principal);
  }

  private static boolean challengeTotpEnAttente(final HttpSession session) {
    return session != null
        && Boolean.TRUE.equals(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE));
  }

  private static UtilisateurDetails principalAuthentifie(final Authentication auth) {
    return auth != null
            && auth.isAuthenticated()
            && auth.getPrincipal() instanceof UtilisateurDetails principal
        ? principal
        : null;
  }

  private ResponseEntity<Map<String, Object>> construireReponseTotp(
      final ResultatTotp resultat, final HttpSession session, final UtilisateurDetails principal) {
    return switch (resultat.statut()) {
      case DEJA_VERROUILLE -> {
        session.invalidate();
        LOG.debug("Compte TOTP verrouillé, session invalidée : {}", principal.identifiant());
        yield ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of(CLE_ERREUR, "Compte verrouillé. Contactez un administrateur."));
      }
      case SUCCES -> {
        session.removeAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE);
        LOG.debug("Challenge TOTP validé : {}", principal.identifiant());
        yield ResponseEntity.ok(Map.of("succes", true));
      }
      case VERROUILLE_MAINTENANT -> {
        session.invalidate();
        LOG.debug("Trop de tentatives TOTP, session invalidée : {}", principal.identifiant());
        yield ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of(CLE_ERREUR, "Trop de tentatives. Compte verrouillé."));
      }
      case ECHEC ->
          ResponseEntity.status(422)
              .body(
                  Map.of(
                      CLE_ERREUR,
                      "Code incorrect. " + resultat.essaisRestants() + " essai(s) restant(s)."));
    };
  }

  /**
   * Retourne l'utilisateur actuellement authentifié.
   *
   * <p>Le champ {@code totpRequis} est {@code true} si un challenge TOTP est encore en attente pour
   * la session courante (l'utilisateur s'est connecté mais n'a pas encore validé le 2FA).
   *
   * @param auth l'authentification Spring Security (injectée automatiquement)
   * @param httpRequest la requête HTTP (pour lire l'état TOTP de la session)
   * @return l'utilisateur courant ou 401 si non authentifié
   */
  @GetMapping("/api/auth/me")
  public ResponseEntity<UtilisateurDto> moi(
      final Authentication auth, final HttpServletRequest httpRequest) {
    if (!(auth != null
        && auth.isAuthenticated()
        && auth.getPrincipal() instanceof UtilisateurDetails details)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    final HttpSession session = httpRequest.getSession(false);
    final boolean totpRequis =
        session != null
            && Boolean.TRUE.equals(
                session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE));
    return ResponseEntity.ok(
        new UtilisateurDto(
            details.id(),
            details.identifiant(),
            details.email(),
            details.role(),
            details.doitChangerCredentials(),
            totpRequis));
  }
}
