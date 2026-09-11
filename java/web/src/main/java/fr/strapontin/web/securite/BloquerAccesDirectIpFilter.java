/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtre bloquant les accès directs par adresse IP.
 *
 * Rejette les requêtes dont le header {@code Host} est une adresse IP
 * (IPv4 avec port optionnel, ou IPv6 entre crochets).
 *
 * En profil {@code dev}, la vérification est ignorée pour faciliter le
 * développement local et les tests E2E depuis une IP privée.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Component
public class BloquerAccesDirectIpFilter extends OncePerRequestFilter {

  private static final Pattern PATTERN_IPV4 =
      Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?$");
  private static final Pattern PATTERN_IPV6 = Pattern.compile("^\\[?[0-9a-fA-F:]+]?(?::\\d+)?$");

  // /setup et ressources statiques exemptés pour permettre la configuration initiale par IP
  private static final Set<String> CHEMINS_EXEMPTS =
      Set.of("/setup", "/api/setup", "/sw.js", "/offline.html");
  private static final String[] PREFIXES_STATIQUES = {"/assets/", "/icons/", "/images/"};

  private final boolean actif;

  public BloquerAccesDirectIpFilter(final Environment env) {
    this.actif = Arrays.stream(env.getActiveProfiles()).noneMatch("dev"::equals);
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    if (!actif) {
      chain.doFilter(request, response);
      return;
    }
    final String chemin = request.getServletPath();
    if (CHEMINS_EXEMPTS.contains(chemin) || estStatique(chemin)) {
      chain.doFilter(request, response);
      return;
    }
    final String host = request.getHeader("Host");
    if (host != null
        && (PATTERN_IPV4.matcher(host).matches() || PATTERN_IPV6.matcher(host).matches())) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"erreur\":\"Accès direct par IP non autorisé\"}");
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean estStatique(final String chemin) {
    for (final String prefixe : PREFIXES_STATIQUES) {
      if (chemin.startsWith(prefixe)) {
        return true;
      }
    }
    return false;
  }
}
