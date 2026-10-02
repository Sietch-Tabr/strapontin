/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.JetonInterneWorker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuse (403) tout appel à l'API interne {@code /trigger} sans jeton valide ({@link
 * JetonInterneWorker}) : seul le module web, qui connaît le secret, peut déclencher un job.
 *
 * @since 3.0.2
 * @author Kwaite
 */
public class FiltreJetonInterne extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(FiltreJetonInterne.class);

  private final String secret;

  /**
   * @param secret valeur de {@code APP_CONFIG_SECRET}
   */
  public FiltreJetonInterne(final String secret) {
    super();
    this.secret = secret;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    if (!JetonInterneWorker.estValide(secret, request.getHeader(JetonInterneWorker.ENTETE))) {
      LOG.warn(
          "appel API interne refusé : jeton absent ou invalide uri={}", request.getRequestURI());
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    chain.doFilter(request, response);
  }
}
