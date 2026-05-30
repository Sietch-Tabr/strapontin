/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/// Gestionnaire post-authentification : redirige vers le challenge TOTP si l'utilisateur
/// a activé le 2FA, sinon vers {@code /}.
///
/// @since 2.0.0
/// @author Kwaite
@Component
public class TotpSuccessHandler implements AuthenticationSuccessHandler {

  static final String SESSION_TOTP_EN_ATTENTE = "TOTP_EN_ATTENTE";

  /// Redirige l'utilisateur après authentification par mot de passe.
  ///
  /// @param request        la requête HTTP
  /// @param response       la réponse HTTP
  /// @param authentication le contexte d'authentification Spring Security
  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    UtilisateurDetails principal = (UtilisateurDetails) authentication.getPrincipal();
    if (principal.twoFactorActif()) {
      request.getSession().setAttribute(SESSION_TOTP_EN_ATTENTE, Boolean.TRUE);
      response.sendRedirect("/auth/totp");
    } else {
      response.sendRedirect("/");
    }
  }
}
