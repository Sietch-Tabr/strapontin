/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.config.ConfigService;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Cohérence d'un réglage de notifications : une combinaison sans effet est
 * refusée plutôt que mémorisée. « Jamais » ne sert aucun canal ; une case ne
 * se coche que si son canal peut servir (appareil abonné, envoi d'emails
 * possible).
 * @since 3.0.6
 * @author Kwaite
 */
final class VerificationNotifications {

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;

  /* package-private : instancié par UserConfigService (même package). */
  VerificationNotifications(
      final NamedParameterJdbcTemplate jdbc, final ConfigService configService) {
    this.jdbc = jdbc;
    this.configService = configService;
  }

  /**
   * @param userId identifiant de l'utilisateur
   * @param requete réglage demandé
   * @param jamais fréquence « jamais » choisie
   * @param contenuEmail un contenu est coché pour l'email
   * @param contenuPush un contenu est coché pour le push
   * @throws ErreurMetier si la combinaison n'a aucun effet
   */
  /* package-private : appelé par UserConfigService (même package). */
  void verifier(
      final String userId,
      final RequeteNotifications requete,
      final boolean jamais,
      final boolean contenuEmail,
      final boolean contenuPush) {
    final boolean nouvelleVersion = requete.notifNouvelleVersion();
    verifierFrequenceEtContenu(jamais, contenuEmail || contenuPush, nouvelleVersion);
    // La nouvelle version suit un contenu coché : par push si un appareil est
    // abonné, sinon par email, canaux que ce contrôle vérifie déjà.
    verifierCanauxDisponibles(userId, contenuEmail, contenuPush);
  }

  private static void verifierFrequenceEtContenu(
      final boolean jamais, final boolean unContenu, final boolean nouvelleVersion) {
    if (jamais && (unContenu || nouvelleVersion)) {
      throw new ErreurMetier(
          "Avec « Jamais », aucune notification n'est envoyée : décochez les types de contenu ou"
              + " choisissez une fréquence.");
    }
    if (!jamais && !unContenu) {
      throw new ErreurMetier(
          "Activez au moins un type de contenu pour recevoir des notifications.");
    }
  }

  private void verifierCanauxDisponibles(
      final String userId, final boolean contenuEmail, final boolean usagePush) {
    if (usagePush && nombreAppareilsPush(userId) == 0) {
      throw new ErreurMetier(
          "Aucun appareil n'est abonné aux notifications push : activez-les d'abord sur cet"
              + " appareil.");
    }
    if (contenuEmail && configService.lireConfig("RESEND_API_KEY").isEmpty()) {
      throw new ErreurMetier("Les emails ne sont pas disponibles pour le moment.");
    }
  }

  private int nombreAppareilsPush(final String userId) {
    final Integer nombre =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM push_abonnements WHERE user_id = :userId",
            Map.of("userId", userId),
            Integer.class);
    return nombre == null ? 0 : nombre;
  }
}
