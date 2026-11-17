/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Réponses 401/403 d'une API refusée (JSON, journalisées dans logs_admin),
 * redirection vers /login pour une page.
 * @since 3.0.2
 * @author Kwaite
 */
final class ReponsesAccesApi {

  private static final Logger LOG = LoggerFactory.getLogger(ReponsesAccesApi.class);

  private ReponsesAccesApi() {}

  /* package-private : point d'entrée d'authentification de SecurityConfiguration. */
  static void echecAuthentification(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final LogAdminService logAdminService)
      throws IOException {
    if (request.getServletPath().startsWith("/api/")) {
      try {
        final Map<String, Object> meta = new HashMap<>();
        meta.put("chemin", request.getServletPath());
        meta.put("ip", request.getRemoteAddr());
        final String ua = request.getHeader("User-Agent");
        meta.put("userAgent", ua != null ? ua.substring(0, Math.min(ua.length(), 200)) : "");
        logAdminService.logAdminSansRepetition(
            "warn", "web", "Accès API non authentifié : " + request.getServletPath(), meta);
      } catch (Exception e) {
        LOG.warn("échec de la journalisation d'un accès non authentifié", e);
      }
      response.setStatus(401);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"erreur\":\"Authentification requise\"}");
    } else {
      response.sendRedirect("/login");
    }
  }

  /* package-private : gestionnaire de refus d'accès de SecurityConfiguration. */
  static void accesRefuse(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final LogAdminService logAdminService)
      throws IOException {
    if (request.getServletPath().startsWith("/api/")) {
      try {
        final Map<String, Object> meta = new HashMap<>();
        meta.put("chemin", request.getServletPath());
        meta.put("ip", request.getRemoteAddr());
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
          meta.put("userId", auth.getName());
        }
        logAdminService.logAdmin(
            "warn", "web", "Accès API refusé : " + request.getServletPath(), meta);
      } catch (Exception e) {
        LOG.warn("échec de la journalisation d'un accès refusé", e);
      }
      response.setStatus(403);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"erreur\":\"Accès refusé\"}");
    } else {
      response.sendRedirect("/login");
    }
  }
}
