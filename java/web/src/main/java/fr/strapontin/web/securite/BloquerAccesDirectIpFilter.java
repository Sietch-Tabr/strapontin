/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/// Filtre bloquant les accès directs par adresse IP.
///
/// <p>Rejette les requêtes dont le header {@code Host} est une adresse IP
/// (IPv4 avec port optionnel, ou IPv6 entre crochets).
///
/// <p>En profil {@code dev}, la vérification est ignorée pour faciliter le
/// développement local et les tests E2E depuis une IP privée.
///
/// @since 2.0.0
/// @author Kwaite
@Component
public class BloquerAccesDirectIpFilter extends OncePerRequestFilter {

  // IPv4 avec port optionnel : 192.168.1.1 ou 192.168.1.1:8080
  // IPv6 entre crochets avec port optionnel : [::1] ou [::1]:8080
  private static final Pattern PATTERN_IP =
      Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?$|^\\[?[0-9a-fA-F:]+]?(?::\\d+)?$");

  private final boolean actif;

  public BloquerAccesDirectIpFilter(Environment env) {
    this.actif = Arrays.stream(env.getActiveProfiles()).noneMatch("dev"::equals);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!actif) {
      chain.doFilter(request, response);
      return;
    }
    String host = request.getHeader("Host");
    if (host != null && PATTERN_IP.matcher(host).matches()) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"erreur\":\"Accès direct par IP non autorisé\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
