/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Gestion des sessions actives ("appareils") d'un utilisateur
 * (détail : CLAUDE.md, Authentification).
 * @since 2.7.8
 * @author Kwaite
 */
@Service
public class AppareilsService {

  private static final Logger LOG = LoggerFactory.getLogger(AppareilsService.class);
  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param jdbc accès JDBC
   */
  public AppareilsService(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Détecte un nouvel appareil pour cet utilisateur — à appeler avant
   * {@link #enregistrerAppareil}, sans quoi la ligne tout juste insérée
   * fausserait le résultat.
   * @param userId identifiant de l'utilisateur
   * @param userAgent en-tête {@code User-Agent}, peut être {@code null}
   * @return {@code true} si un nouvel appareil est détecté
   */
  public boolean estNouvelAppareil(final String userId, final String userAgent) {
    final String ua = userAgent == null ? "" : userAgent;
    try {
      final Map<String, Object> resultat =
          jdbc.queryForMap(
              "SELECT COUNT(*) AS total,"
                  + " COUNT(*) FILTER (WHERE user_agent = :userAgent) AS meme_appareil"
                  + " FROM session_appareils WHERE user_id = :userId",
              Map.of(CLE_USER_ID, userId, "userAgent", ua));
      final long total = ((Number) resultat.get("total")).longValue();
      final long memeAppareil = ((Number) resultat.get("meme_appareil")).longValue();
      return total > 0 && memeAppareil == 0;
    } catch (DataAccessException e) {
      LOG.warn("échec de détection de nouvel appareil, ignoré : {}", e.getMessage());
      return false;
    }
  }

  /**
   * Enregistre l'appareil associé à une session déjà persistée
   * (détail : CLAUDE.md, Authentification) — best-effort, ne
   * fait jamais échouer la requête appelante.
   * @param sessionId identifiant de session Spring Session
   * @param userId identifiant de l'utilisateur
   * @param userAgent en-tête {@code User-Agent}, peut être {@code null}
   */
  public void enregistrerAppareil(
      final String sessionId, final String userId, final String userAgent) {
    try {
      jdbc.update(
          "INSERT INTO session_appareils (session_id, user_id, user_agent)"
              + " VALUES (:sessionId, :userId, :userAgent)"
              + " ON CONFLICT (session_id) DO NOTHING",
          Map.of(
              "sessionId",
              sessionId,
              CLE_USER_ID,
              userId,
              "userAgent",
              userAgent == null ? "" : userAgent));
    } catch (DataAccessException e) {
      LOG.warn("échec d'enregistrement de l'appareil pour la session, ignoré : {}", e.getMessage());
    }
  }

  /**
   * Liste les appareils (sessions actives), les plus récemment
   * actifs en tête.
   * @param userId identifiant de l'utilisateur
   * @param sessionIdCourante identifiant de la session courante
   *   (jamais exposé tel quel), pour marquer l'appareil utilisé
   * @return les appareils actifs
   */
  public List<AppareilDto> listerAppareils(final String userId, final String sessionIdCourante) {
    return jdbc.query(
        "SELECT sa.appareil_id AS appareil_id, sa.session_id AS session_id,"
            + "   s.CREATION_TIME AS creation_time, s.LAST_ACCESS_TIME AS last_access_time,"
            + "   sa.user_agent"
            + " FROM SPRING_SESSION s"
            + " JOIN session_appareils sa ON sa.session_id = s.SESSION_ID"
            + " WHERE sa.user_id = :userId"
            + " ORDER BY s.LAST_ACCESS_TIME DESC",
        Map.of(CLE_USER_ID, userId),
        (rs, _) ->
            new AppareilDto(
                rs.getObject("appareil_id", UUID.class).toString(),
                rs.getString("user_agent"),
                Instant.ofEpochMilli(rs.getLong("creation_time")),
                Instant.ofEpochMilli(rs.getLong("last_access_time")),
                rs.getString("session_id").equals(sessionIdCourante)));
  }

  /**
   * Révoque un appareil (supprime sa session) — n'agit que sur
   * les sessions de l'utilisateur demandeur.
   * @param userId identifiant de l'utilisateur demandeur
   * @param appareilId identifiant opaque de l'appareil à révoquer
   * @return {@code true} si une session a été supprimée
   */
  public boolean revoquerAppareil(final String userId, final String appareilId) {
    final int supprimees =
        jdbc.update(
            "DELETE FROM SPRING_SESSION WHERE SESSION_ID IN"
                + " (SELECT session_id FROM session_appareils"
                + "  WHERE CAST(appareil_id AS text) = :appareilId AND user_id = :userId)",
            Map.of("appareilId", appareilId, CLE_USER_ID, userId));
    return supprimees > 0;
  }

  /**
   * Appareil (session active) exposé côté client, identifiant
   * opaque sans rapport avec la session réelle.
   */
  public record AppareilDto(
      String appareilId,
      String userAgent,
      Instant creeLe,
      Instant derniereActivite,
      boolean sessionCourante) {}
}
