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
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtre posant un identifiant de corrélation dans le {@link MDC} Logback.
 *
 * <p>Si la requête entrante contient un header {@code X-Correlation-ID}, il est réutilisé.
 * Sinon, un UUID aléatoire est généré. L'identifiant est retourné dans la réponse
 * via le même header, et retiré du MDC après traitement.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

  private static final String CLE_MDC = "correlationId";
  private static final String HEADER = "X-Correlation-ID";
  private static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("[a-zA-Z0-9\\-]{1,64}");

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    final String entrant = request.getHeader(HEADER);
    final String id =
        (entrant != null && CORRELATION_ID_PATTERN.matcher(entrant).matches())
            ? entrant
            : UUID.randomUUID().toString();
    MDC.put(CLE_MDC, id);
    response.setHeader(HEADER, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(CLE_MDC);
    }
  }
}
