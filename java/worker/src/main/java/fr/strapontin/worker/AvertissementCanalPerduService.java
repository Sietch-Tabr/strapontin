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
 * Prévient par email, une fois par perte, l'utilisateur dont le dernier appareil
 * push a été retiré (quels que soient ses autres canaux) ou dont le push seul
 * coché n'a jamais eu d'appareil. Réarmé dès qu'un appareil est de nouveau abonné.
 * @since 3.0.3
 * @author Kwaite
 */
@Service
public class AvertissementCanalPerduService {

  private static final Logger LOG = LoggerFactory.getLogger(AvertissementCanalPerduService.class);

  // Un push jamais abonné n'est signalé qu'une fois une notification réellement
  // manquée : l'abonnement suit de quelques secondes l'activation, et l'accueil
  // ne doit pas déclencher d'email.
  private static final String MATCH_EN_ATTENTE_SQL =
      "EXISTS (SELECT 1 FROM user_matches um WHERE um.user_id = uc.user_id"
          + " AND um.notification_envoyee_le IS NULL AND um.masque_le IS NULL"
          + " AND um.cree_le < now() - interval '1 day')";

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
   * Avertit les comptes qui viennent de perdre leur dernier canal, ou dont le
   * push n'a jamais pris effet alors qu'un concert attend, et réarme ceux qui
   * en ont retrouvé un.
   *
   * @return nombre d'avertissements envoyés
   */
  public int verifier() {
    jdbc.update(
        "UPDATE user_config uc SET avertissement_canal_perdu_le = NULL"
            + " WHERE uc.avertissement_canal_perdu_le IS NOT NULL AND NOT (("
            + CanalNotificationPerdu.CONDITION_SQL
            + ") OR ("
            + CanalNotificationPerdu.CONDITION_JAMAIS_ABONNE_SQL
            + "))",
        Map.of());
    final List<Map<String, Object>> comptes =
        jdbc.queryForList(
            "SELECT uc.user_id, u.email, uc.cause_dernier_retrait_appareil AS cause,"
                + " uc.dernier_appareil_retire_le IS NULL AS jamais_abonne"
                + " FROM user_config uc JOIN users u ON u.id = uc.user_id"
                + " WHERE uc.avertissement_canal_perdu_le IS NULL AND u.email IS NOT NULL AND (("
                + CanalNotificationPerdu.CONDITION_SQL
                + ") OR ("
                + CanalNotificationPerdu.CONDITION_JAMAIS_ABONNE_SQL
                + " AND "
                + MATCH_EN_ATTENTE_SQL
                + "))",
            Map.of());
    int envoyes = 0;
    for (final Map<String, Object> compte : comptes) {
      final String email = (String) compte.get("email");
      final boolean envoye =
          Boolean.TRUE.equals(compte.get("jamais_abonne"))
              ? emailCompteService.envoyerAvertissementPushJamaisActive(email)
              : emailCompteService.envoyerAvertissementCanalPerdu(
                  email, (String) compte.get("cause"));
      // Non marqué en cas d'échec : nouvelle tentative au cycle suivant.
      if (envoye) {
        jdbc.update(
            "UPDATE user_config SET avertissement_canal_perdu_le = now() WHERE user_id = :userId",
            Map.of("userId", compte.get("user_id")));
        envoyes++;
      }
    }
    if (envoyes > 0) {
      LOG.info("Avertissements de compte sans canal de notification envoyés : {}", envoyes);
    }
    return envoyes;
  }
}
