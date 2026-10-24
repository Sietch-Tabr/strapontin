/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Tient {@code users.derniere_connexion_le} à jour sur l'activité réelle, au plus
 * une fois par jour et par utilisateur ; une activité annule aussi une purge
 * d'inactivité en cours. Le marquage vit en mémoire : un attribut de session,
 * posé en parallèle par la première rafale, violait la clé de la table.
 * @since 3.0.2
 * @author Kwaite
 */
@Component
public class ActiviteUtilisateurInterceptor implements HandlerInterceptor {

  private static final Duration INTERVALLE_MARQUAGE = Duration.ofDays(1);
  private static final long UTILISATEURS_SUIVIS_MAX = 10_000;

  private final NamedParameterJdbcTemplate jdbc;
  private final Cache<String, Boolean> marquages;

  /**
   * @param jdbc accès JDBC (mise à jour de la dernière activité)
   */
  @Autowired
  public ActiviteUtilisateurInterceptor(final NamedParameterJdbcTemplate jdbc) {
    this(jdbc, Ticker.systemTicker());
  }

  /* package-private : horloge injectée par les tests. */
  ActiviteUtilisateurInterceptor(final NamedParameterJdbcTemplate jdbc, final Ticker horloge) {
    this.jdbc = jdbc;
    this.marquages =
        Caffeine.newBuilder()
            .maximumSize(UTILISATEURS_SUIVIS_MAX)
            .expireAfterWrite(INTERVALLE_MARQUAGE)
            .ticker(horloge)
            .build();
  }

  @Override
  public boolean preHandle(
      final HttpServletRequest request, final HttpServletResponse response, final Object handler) {
    if (request.getSession(false) == null) {
      return true;
    }
    final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.isAuthenticated()
        && auth.getPrincipal() instanceof UtilisateurDetails details) {
      // Un seul thread écrit par utilisateur et par jour, les autres l'attendent.
      marquages.asMap().computeIfAbsent(details.id(), this::marquer);
    }
    return true;
  }

  private Boolean marquer(final String id) {
    jdbc.update(
        "UPDATE users SET derniere_connexion_le = now(), purge_le = NULL WHERE id = :id",
        Map.of("id", id));
    return Boolean.TRUE;
  }
}
