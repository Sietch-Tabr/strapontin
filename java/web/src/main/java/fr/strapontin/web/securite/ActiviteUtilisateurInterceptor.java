/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Tient {@code users.derniere_connexion_le} à jour sur l'activité réelle (une
 * session dure 30 jours sans reconnexion), au plus une fois par jour et par
 * session ; une activité annule aussi une purge d'inactivité en cours.
 * @since 3.0.2
 * @author Kwaite
 */
@Component
public class ActiviteUtilisateurInterceptor implements HandlerInterceptor {

  private static final String ATTRIBUT_ACTIVITE_MARQUEE_LE = "activiteMarqueeLe";
  private static final Duration INTERVALLE_MARQUAGE = Duration.ofDays(1);

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param jdbc accès JDBC (mise à jour de la dernière activité)
   */
  public ActiviteUtilisateurInterceptor(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean preHandle(
      final HttpServletRequest request, final HttpServletResponse response, final Object handler) {
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return true;
    }
    final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || !(auth.getPrincipal() instanceof UtilisateurDetails details)) {
      return true;
    }
    final Instant maintenant = Instant.now();
    if (session.getAttribute(ATTRIBUT_ACTIVITE_MARQUEE_LE) instanceof Instant derniere
        && derniere.plus(INTERVALLE_MARQUAGE).isAfter(maintenant)) {
      return true;
    }
    jdbc.update(
        "UPDATE users SET derniere_connexion_le = now(), purge_le = NULL WHERE id = :id",
        Map.of("id", details.id()));
    session.setAttribute(ATTRIBUT_ACTIVITE_MARQUEE_LE, maintenant);
    return true;
  }
}
