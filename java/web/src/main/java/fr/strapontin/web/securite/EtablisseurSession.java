/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * Invalidation de la session existante et ouverture d'une nouvelle session pour une
 * authentification déjà validée — partagé entre {@link AuthController#login} et le flux
 * d'activation d'invitation.
 *
 * <p>Extrait d'{@link AuthController} pour rester sous le seuil de couplage entre objets (20).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class EtablisseurSession {

  private EtablisseurSession() {}

  /** Résultat de {@link #ouvrir} : la session nouvellement créée et l'utilisateur associé. */
  record SessionOuverte(HttpSession session, UtilisateurDetails details) {}

  /**
   * @param auth authentification déjà validée par l'{@code AuthenticationManager}
   * @param httpRequest la requête HTTP (pour invalider/créer la session)
   * @param messageErreur message de l'exception si le principal n'est pas du type attendu
   * @return la session nouvellement créée et les détails de l'utilisateur authentifié
   */
  static SessionOuverte ouvrir(
      final Authentication auth, final HttpServletRequest httpRequest, final String messageErreur) {
    final HttpSession sessionExistante = httpRequest.getSession(false);
    if (sessionExistante != null) {
      sessionExistante.invalidate();
    }

    final SecurityContext contexte = SecurityContextHolder.createEmptyContext();
    contexte.setAuthentication(auth);
    SecurityContextHolder.setContext(contexte);

    final HttpSession session = httpRequest.getSession(true);
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, contexte);

    if (!(auth.getPrincipal() instanceof UtilisateurDetails details)) {
      throw new IllegalStateException(messageErreur);
    }
    return new SessionOuverte(session, details);
  }
}
