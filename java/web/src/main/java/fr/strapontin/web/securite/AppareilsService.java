/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Gestion des sessions actives ("appareils") d'un utilisateur.
///
/// <p>{@code SPRING_SESSION} (schéma tiers géré par Spring Session JDBC) ne porte aucune colonne
/// exploitable pour identifier un appareil — {@code session_appareils} lui est associée séparément
/// à la connexion, sans modifier son schéma. Le véritable {@code session_id} n'est jamais exposé
/// côté client : un {@code appareil_id} opaque sert d'identifiant public, sans rapport avec le
/// cookie {@code SESSION} — sinon un XSS pourrait lire la liste et usurper n'importe quelle session
/// active de l'utilisateur, pas seulement celle déjà compromise.
///
/// @since 2.7.8
/// @author Kwaite
@Service
public class AppareilsService {

  private static final Logger log = LoggerFactory.getLogger(AppareilsService.class);

  private final NamedParameterJdbcTemplate jdbc;

  /// @param jdbc accès JDBC
  public AppareilsService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Enregistre l'appareil associé à une session déjà persistée.
  ///
  /// <p>Best-effort : appelé depuis {@link AppareilEnregistrementInterceptor}, jamais depuis le
  /// contrôleur de login lui-même — la ligne {@code SPRING_SESSION} référencée par la clé étrangère
  /// n'existe pas encore au moment où ce dernier répond (voir la Javadoc de l'intercepteur). Une
  /// erreur ici (ex. session invalidée entre-temps par une déconnexion concurrente) ne doit jamais
  /// faire échouer la requête qui a déclenché l'enregistrement.
  ///
  /// @param sessionId identifiant de session Spring Session
  /// @param userId identifiant de l'utilisateur
  /// @param userAgent en-tête {@code User-Agent} de la requête de connexion, peut être {@code null}
  public void enregistrerAppareil(String sessionId, String userId, String userAgent) {
    try {
      jdbc.update(
          "INSERT INTO session_appareils (session_id, user_id, user_agent)"
              + " VALUES (:sessionId, :userId, :userAgent)"
              + " ON CONFLICT (session_id) DO NOTHING",
          Map.of(
              "sessionId",
              sessionId,
              "userId",
              userId,
              "userAgent",
              userAgent == null ? "" : userAgent));
    } catch (DataAccessException e) {
      log.warn("échec d'enregistrement de l'appareil pour la session, ignoré : {}", e.getMessage());
    }
  }

  /// Liste les appareils (sessions actives) de l'utilisateur, les plus récemment actifs en tête.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param sessionIdCourante identifiant de la session de la requête courante (jamais exposé tel
  ///   quel), pour marquer l'appareil actuellement utilisé
  /// @return les appareils actifs
  public List<AppareilDto> listerAppareils(String userId, String sessionIdCourante) {
    return jdbc.query(
        "SELECT sa.appareil_id AS appareil_id, sa.session_id AS session_id,"
            + "   s.CREATION_TIME AS creation_time, s.LAST_ACCESS_TIME AS last_access_time,"
            + "   sa.user_agent"
            + " FROM SPRING_SESSION s"
            + " JOIN session_appareils sa ON sa.session_id = s.SESSION_ID"
            + " WHERE sa.user_id = :userId"
            + " ORDER BY s.LAST_ACCESS_TIME DESC",
        Map.of("userId", userId),
        (rs, _) ->
            new AppareilDto(
                rs.getObject("appareil_id", java.util.UUID.class).toString(),
                rs.getString("user_agent"),
                Instant.ofEpochMilli(rs.getLong("creation_time")),
                Instant.ofEpochMilli(rs.getLong("last_access_time")),
                rs.getString("session_id").equals(sessionIdCourante)));
  }

  /// Révoque un appareil (supprime sa session) — n'agit que sur les sessions appartenant à
  /// l'utilisateur demandeur, empêchant de révoquer la session d'un autre utilisateur en devinant
  /// un identifiant d'appareil.
  ///
  /// @param userId identifiant de l'utilisateur demandeur
  /// @param appareilId identifiant opaque de l'appareil à révoquer
  /// @return {@code true} si une session a effectivement été supprimée
  public boolean revoquerAppareil(String userId, String appareilId) {
    int supprimees =
        jdbc.update(
            "DELETE FROM SPRING_SESSION WHERE SESSION_ID IN"
                + " (SELECT session_id FROM session_appareils"
                + "  WHERE CAST(appareil_id AS text) = :appareilId AND user_id = :userId)",
            Map.of("appareilId", appareilId, "userId", userId));
    return supprimees > 0;
  }

  /// Appareil (session active) exposé côté client.
  ///
  /// @param appareilId identifiant opaque de l'appareil, sans rapport avec la session réelle
  /// @param userAgent en-tête User-Agent capturé à la connexion, peut être vide
  /// @param creeLe date de création de la session
  /// @param derniereActivite date de dernière activité de la session
  /// @param sessionCourante {@code true} si c'est la session de la requête en cours
  public record AppareilDto(
      String appareilId,
      String userAgent,
      Instant creeLe,
      Instant derniereActivite,
      boolean sessionCourante) {}
}
