/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/// Filtre de rate limiting sur l'endpoint de login.
///
/// <p>Limite à 5 tentatives par adresse IP sur une fenêtre glissante de 15 minutes.
/// Les buckets sont stockés en Caffeine (perte acceptable au redémarrage —
/// la fenêtre de 15 min reste cohérente en pratique).
///
/// @since 2.0.0
/// @author Kwaite
@Component
public class RateLimitingLoginFilter extends OncePerRequestFilter {

  private static final int MAX_TENTATIVES = 5;
  private static final Duration FENETRE = Duration.ofMinutes(15);
  private static final String URL_LOGIN = "/api/auth/login";

  private final Cache<String, Bucket> buckets =
      Caffeine.newBuilder().expireAfterWrite(FENETRE.plusMinutes(1)).maximumSize(10_000).build();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!HttpMethod.POST.matches(request.getMethod())
        || !URL_LOGIN.equals(request.getServletPath())) {
      chain.doFilter(request, response);
      return;
    }

    String ip = obtenirIp(request);
    Bucket bucket = buckets.get(ip, _ -> creerBucket());

    if (bucket.tryConsume(1)) {
      chain.doFilter(request, response);
    } else {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      response
          .getWriter()
          .write("{\"erreur\":\"Trop de tentatives de connexion. Réessayez dans 15 minutes.\"}");
    }
  }

  private Bucket creerBucket() {
    Bandwidth limite =
        Bandwidth.builder()
            .capacity(MAX_TENTATIVES)
            .refillIntervally(MAX_TENTATIVES, FENETRE)
            .build();
    return Bucket.builder().addLimit(limite).build();
  }

  private String obtenirIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].strip();
    }
    return request.getRemoteAddr();
  }
}
