/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.config.ConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuse (403) une requête modifiante sur /api/** venue d'une autre origine,
 * sous-domaine voisin compris : Sec-Fetch-Site, Origin, et en-tête applicatif
 * (preflight CORS impossible ailleurs). Hors @Component : placé après
 * l'autorisation. Exclus : /api/webhooks/ et /api/interne/ (serveur à serveur).
 * @since 3.0.2
 * @author Kwaite
 */
public class ProtectionCsrfFilter extends OncePerRequestFilter {

  /** En-tête applicatif exigé, posé par {@code front/src/utils/api.js}. */
  public static final String ENTETE_APPLICATION = "X-Requested-With";

  /** Valeur attendue de l'en-tête applicatif. */
  public static final String VALEUR_ENTETE_APPLICATION = "strapontin";

  private static final Logger LOG = LoggerFactory.getLogger(ProtectionCsrfFilter.class);
  private static final Set<String> METHODES_MODIFIANTES = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final Set<String> SITES_ACCEPTES = Set.of("same-origin", "none");
  private static final List<String> PREFIXES_EXCLUS = List.of("/api/webhooks/", "/api/interne/");

  private final ConfigService configService;

  /**
   * @param configService lecture de {@code PUBLIC_BASE_URL}
   */
  public ProtectionCsrfFilter(final ConfigService configService) {
    super();
    this.configService = configService;
  }

  @Override
  protected boolean shouldNotFilter(final HttpServletRequest request) {
    final String chemin = request.getRequestURI();
    return !METHODES_MODIFIANTES.contains(request.getMethod())
        || !chemin.startsWith("/api/")
        || PREFIXES_EXCLUS.stream().anyMatch(chemin::startsWith);
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    final String motif = motifRefus(request);
    if (motif != null) {
      LOG.warn(
          "requête refusée (protection CSRF) : {} {} {}",
          motif,
          request.getMethod(),
          request.getRequestURI());
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write("{\"erreur\":\"Requête refusée.\"}");
      return;
    }
    chain.doFilter(request, response);
  }

  private String motifRefus(final HttpServletRequest request) {
    final String site = request.getHeader("Sec-Fetch-Site");
    if (site != null && !SITES_ACCEPTES.contains(site.toLowerCase(Locale.ROOT))) {
      return "Sec-Fetch-Site=" + site;
    }
    final String origine = request.getHeader("Origin");
    if (origine != null && !estOrigineApplication(origine, request)) {
      return "Origin=" + origine;
    }
    if (!VALEUR_ENTETE_APPLICATION.equals(request.getHeader(ENTETE_APPLICATION))) {
      return "en-tête applicatif absent";
    }
    return null;
  }

  private boolean estOrigineApplication(final String origine, final HttpServletRequest request) {
    final String normalisee = normaliserOrigine(origine);
    if (normalisee == null) {
      return false;
    }
    return normalisee.equals(origineRequete(request))
        || configService
            .lireConfig("PUBLIC_BASE_URL")
            .map(ProtectionCsrfFilter::normaliserOrigine)
            .filter(normalisee::equals)
            .isPresent();
  }

  // Derrière le proxy, schéma, hôte et port reflètent X-Forwarded-*.
  private static String origineRequete(final HttpServletRequest request) {
    return normaliserOrigine(
        request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort());
  }

  // Forme canonique schéma://hôte[:port non standard], null si l'URL est invalide.
  private static String normaliserOrigine(final String url) {
    try {
      final URI uri = URI.create(url.strip());
      if (uri.getScheme() == null || uri.getHost() == null) {
        return null;
      }
      final String schema = uri.getScheme().toLowerCase(Locale.ROOT);
      final int port = uri.getPort();
      final boolean portStandard =
          port == -1
              || ("https".equals(schema) && port == 443)
              || ("http".equals(schema) && port == 80);
      return schema
          + "://"
          + uri.getHost().toLowerCase(Locale.ROOT)
          + (portStandard ? "" : ":" + port);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
