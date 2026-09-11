/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.web.admin.LogAdminService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtre de rate limiting sur les endpoints d'authentification publics.
 *
 * <p>Limites par endpoint :
 * <ul>
 *   <li>{@code /api/auth/login} : 15 tentatives / 15 minutes par IP
 *   <li>{@code /api/auth/forgot-password} : 15 tentatives / 15 minutes par IP
 *   <li>{@code /api/auth/reset-password} : 30 tentatives / heure par IP
 * </ul>
 *
 * <p><b>Persistance :</b> les buckets sont stockés en mémoire Caffeine et perdus au redémarrage
 * de l'application. Les tentatives bloquées (HTTP 429) sont loguées dans {@code logs_admin}
 * (source {@code auth}, niveau {@code warn}) pour permettre leur surveillance. Le risque résiduel
 * est acceptable pour un système invite-only ; migration vers un stockage persistant
 * (PostgreSQL) à envisager si l'exposition publique évolue.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Component
public class RateLimitingLoginFilter extends OncePerRequestFilter {

  private static final int MAX_TENTATIVES_LOGIN = 15;
  private static final Duration FENETRE_LOGIN = Duration.ofMinutes(15);

  private static final int MAX_TENTATIVES_RESET = 30;
  private static final Duration FENETRE_RESET = Duration.ofHours(1);

  private static final Set<String> URLS_LOGIN =
      Set.of("/api/auth/login", "/api/auth/forgot-password");
  private static final String URL_RESET_PASSWORD = "/api/auth/reset-password";

  private final LogAdminService logAdminService;

  private final Cache<String, Bucket> buckets =
      Caffeine.newBuilder()
          .expireAfterWrite(FENETRE_RESET.plusMinutes(5))
          .maximumSize(10_000)
          .build();

  public RateLimitingLoginFilter(final LogAdminService logAdminService) {
    this.logAdminService = logAdminService;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    final String chemin = request.getServletPath();
    if (!HttpMethod.POST.matches(request.getMethod())
        || (!URLS_LOGIN.contains(chemin) && !URL_RESET_PASSWORD.equals(chemin))) {
      chain.doFilter(request, response);
      return;
    }

    final String ip = request.getRemoteAddr();
    final String cleBucket = ip + ":" + chemin;
    final boolean estReset = URL_RESET_PASSWORD.equals(chemin);
    final Bucket bucket = buckets.get(cleBucket, _ -> estReset ? creerBucketReset() : creerBucketLogin());

    if (bucket.tryConsume(1)) {
      chain.doFilter(request, response);
    } else {
      logAdminService.logAdmin(
          "warn",
          "auth",
          "Rate limit connexion atteint : " + ip + " sur " + chemin,
          Map.of("ip", ip));
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      response
          .getWriter()
          .write("{\"erreur\":\"Trop de tentatives. Réessayez dans quelques minutes.\"}");
    }
  }

  private Bucket creerBucketLogin() {
    final Bandwidth limite =
        Bandwidth.builder()
            .capacity(MAX_TENTATIVES_LOGIN)
            .refillIntervally(MAX_TENTATIVES_LOGIN, FENETRE_LOGIN)
            .build();
    return Bucket.builder().addLimit(limite).build();
  }

  private Bucket creerBucketReset() {
    final Bandwidth limite =
        Bandwidth.builder()
            .capacity(MAX_TENTATIVES_RESET)
            .refillIntervally(MAX_TENTATIVES_RESET, FENETRE_RESET)
            .build();
    return Bucket.builder().addLimit(limite).build();
  }
}
