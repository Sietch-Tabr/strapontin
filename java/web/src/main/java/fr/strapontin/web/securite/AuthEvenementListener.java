/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Écoute les événements Spring Security et les consigne dans {@code logs_admin}.
 *
 * Couvre : connexion réussie (info), échec d'authentification (warn).
 * Le rate-limit est géré séparément par [RateLimitingLoginFilter].
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Component
public class AuthEvenementListener {

  private final LogAdminService logAdminService;

  public AuthEvenementListener(final LogAdminService logAdminService) {
    this.logAdminService = logAdminService;
  }

  /**
   * Consigne une connexion réussie.
   *
   * @param event l'événement Spring Security
   */
  @EventListener
  public void surSuccesAuthentification(final AuthenticationSuccessEvent event) {
    final String identifiant = event.getAuthentication().getName();
    final String ip = obtenirIp(event.getAuthentication());
    final Map<String, Object> meta = new HashMap<>();
    meta.put("identifiant", identifiant);
    meta.put("ip", ip != null ? ip : "?");
    if (event.getAuthentication().getPrincipal() instanceof UtilisateurDetails ud) {
      meta.put("userId", ud.id());
    }
    logAdminService.logAdmin("info", "auth", "Connexion réussie : " + identifiant, meta);
  }

  /**
   * Consigne un échec d'authentification.
   *
   * @param event l'événement Spring Security
   */
  @EventListener
  public void surEchecAuthentification(final AbstractAuthenticationFailureEvent event) {
    final String identifiant = event.getAuthentication().getName();
    final String ip = obtenirIp(event.getAuthentication());
    final String raison =
        event.getException().getClass().getSimpleName().replace("Exception", "").toLowerCase();
    final Map<String, Object> meta = new HashMap<>();
    meta.put("identifiant", identifiant);
    meta.put("ip", ip != null ? ip : "?");
    meta.put("raison", raison);
    logAdminService.logAdmin("warn", "auth", "Échec connexion : " + identifiant, meta);
  }

  private String obtenirIp(final Authentication auth) {
    if (auth.getDetails() instanceof WebAuthenticationDetails details) {
      return details.getRemoteAddress();
    }
    return null;
  }
}
