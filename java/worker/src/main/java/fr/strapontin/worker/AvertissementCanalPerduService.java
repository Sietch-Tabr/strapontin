/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.CanalNotificationPerdu;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Prévient par email, une seule fois par perte, l'utilisateur dont le
 * retrait automatique du dernier appareil push laisse des notifications
 * actives sans aucun canal joignable. L'avertissement est effacé dès qu'un
 * canal redevient joignable, pour qu'une perte ultérieure soit signalée.
 * @since 3.0.3
 * @author Kwaite
 */
@Service
public class AvertissementCanalPerduService {

  private static final Logger LOG = LoggerFactory.getLogger(AvertissementCanalPerduService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailCompteService emailCompteService;

  /**
   * @param jdbc accès JDBC
   * @param emailCompteService envoi de l'avertissement
   */
  public AvertissementCanalPerduService(
      final NamedParameterJdbcTemplate jdbc, final EmailCompteService emailCompteService) {
    this.jdbc = jdbc;
    this.emailCompteService = emailCompteService;
  }

  /**
   * Avertit les comptes qui viennent de perdre leur dernier canal et réarme
   * ceux qui en ont retrouvé un.
   *
   * @return nombre d'avertissements envoyés
   */
  public int verifier() {
    jdbc.update(
        "UPDATE user_config uc SET avertissement_canal_perdu_le = NULL"
            + " WHERE uc.avertissement_canal_perdu_le IS NOT NULL AND NOT ("
            + CanalNotificationPerdu.CONDITION_SQL
            + ")",
        Map.of());
    final List<Map<String, Object>> comptes =
        jdbc.queryForList(
            "SELECT uc.user_id, u.email FROM user_config uc JOIN users u ON u.id = uc.user_id"
                + " WHERE uc.avertissement_canal_perdu_le IS NULL AND u.email IS NOT NULL AND "
                + CanalNotificationPerdu.CONDITION_SQL,
            Map.of());
    int envoyes = 0;
    for (final Map<String, Object> compte : comptes) {
      // Non marqué en cas d'échec : nouvelle tentative au cycle suivant.
      if (emailCompteService.envoyerAvertissementCanalPerdu((String) compte.get("email"))) {
        jdbc.update(
            "UPDATE user_config SET avertissement_canal_perdu_le = now() WHERE user_id = :userId",
            Map.of("userId", compte.get("user_id")));
        envoyes++;
      }
    }
    if (envoyes > 0) {
      LOG.info("Avertissements de perte du dernier canal de notification envoyés : {}", envoyes);
    }
    return envoyes;
  }
}
