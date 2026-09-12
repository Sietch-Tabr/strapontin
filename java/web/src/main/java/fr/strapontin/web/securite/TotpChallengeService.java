/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.utilisateur.UserController;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Vérifie un challenge TOTP post-login et gère le compteur d'échecs/verrouillage.
 *
 * <p>Extrait de {@code AuthController.verifierTotp} — le contrôleur ne portait pas seulement la
 * vérification du code (déjà déléguée à {@link TotpService}) mais aussi toute la logique métier de
 * compteur d'échecs et de verrouillage, en violation du pattern Controller (HTTP pur) → Service →
 * Repository.
 *
 * @since 2.7.0
 * @author Kwaite
 */
@Service
public class TotpChallengeService {

  static final int MAX_ECHECS_TOTP = 5;

  private final NamedParameterJdbcTemplate jdbc;
  private final UserController userController;
  private final TotpService totpService;
  private final LogAdminService logAdminService;

  /**
   * @param jdbc accès JDBC pour le compteur d'échecs
   * @param userController accès au secret TOTP déchiffré ({@code UtilisateurRepository} est
   *     package-privé, hors du package {@code securite})
   * @param totpService vérification du code TOTP (RFC 6238)
   * @param logAdminService journalisation des succès/échecs dans {@code logs_admin}
   */
  public TotpChallengeService(
      final NamedParameterJdbcTemplate jdbc,
      final UserController userController,
      final TotpService totpService,
      final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.userController = userController;
    this.totpService = totpService;
    this.logAdminService = logAdminService;
  }

  /**
   * Challenge TOTP à vérifier.
   *
   * @param userId identifiant de l'utilisateur
   * @param identifiant identifiant lisible (pour les logs)
   * @param code code à 6 chiffres saisi
   * @param ip adresse IP de la requête (pour les logs)
   */
  public record ChallengeTotp(String userId, String identifiant, String code, String ip) {}

  /**
   * Vérifie le code TOTP soumis pour un utilisateur et met à jour le compteur d'échecs.
   *
   * @param challenge le challenge à vérifier (voir {@link ChallengeTotp})
   * @return le résultat du challenge
   */
  public ResultatTotp verifier(final ChallengeTotp challenge) {
    final String userId = challenge.userId();
    final String identifiant = challenge.identifiant();
    final String code = challenge.code();
    final String ip = challenge.ip();
    final int echecsCourants = userController.lireEchecsTotp(userId);
    if (echecsCourants >= MAX_ECHECS_TOTP) {
      return ResultatTotp.dejaVerrouille();
    }

    final String secret = userController.lireSecretTotp(userId);
    if (totpService.verifierCode(secret, code)) {
      jdbc.update("UPDATE users SET totp_failure_count = 0 WHERE id = :id", Map.of("id", userId));
      logAdminService.logAdmin(
          "info",
          "auth",
          "TOTP validé : " + identifiant,
          Map.of("identifiant", identifiant, "ip", ip));
      return ResultatTotp.succes();
    }

    final int nouveauxEchecs = echecsCourants + 1;
    jdbc.update(
        "UPDATE users SET totp_failure_count = totp_failure_count + 1 WHERE id = :id",
        Map.of("id", userId));
    logAdminService.logAdmin(
        "warn",
        "auth",
        "Échec TOTP : " + identifiant,
        Map.of("identifiant", identifiant, "ip", ip, "essais", nouveauxEchecs));

    if (nouveauxEchecs >= MAX_ECHECS_TOTP) {
      return ResultatTotp.verrouilleMaintenant();
    }
    return ResultatTotp.echec(MAX_ECHECS_TOTP - nouveauxEchecs);
  }
}
