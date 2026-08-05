/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Vérifie un challenge TOTP post-login et gère le compteur d'échecs/verrouillage.
///
/// <p>Extrait de {@code AuthController.verifierTotp} — le contrôleur ne portait pas
/// seulement la vérification du code (déjà déléguée à {@link TotpService}) mais aussi toute la
/// logique métier de compteur d'échecs et de verrouillage, en violation du pattern
/// Controller (HTTP pur) → Service → Repository.
///
/// @since 2.7.0
/// @author Kwaite
@Service
public class TotpChallengeService {

  static final int MAX_ECHECS_TOTP = 5;

  private final NamedParameterJdbcTemplate jdbc;
  private final TotpService totpService;
  private final LogAdminService logAdminService;

  /// @param jdbc            accès JDBC pour le secret TOTP et le compteur d'échecs
  /// @param totpService      vérification du code TOTP (RFC 6238)
  /// @param logAdminService journalisation des succès/échecs dans {@code logs_admin}
  public TotpChallengeService(
      NamedParameterJdbcTemplate jdbc, TotpService totpService, LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.totpService = totpService;
    this.logAdminService = logAdminService;
  }

  /// Vérifie le code TOTP soumis pour un utilisateur et met à jour le compteur d'échecs.
  ///
  /// @param userId      identifiant de l'utilisateur
  /// @param identifiant identifiant lisible (pour les logs)
  /// @param code        code à 6 chiffres saisi
  /// @param ip          adresse IP de la requête (pour les logs)
  /// @return le résultat du challenge
  public ResultatTotp verifier(String userId, String identifiant, String code, String ip) {
    Map<String, Object> utilisateur =
        jdbc.queryForMap(
            "SELECT two_factor_secret, totp_failure_count FROM users WHERE id = :id",
            Map.of("id", userId));

    int echecsCourants = ((Number) utilisateur.get("totp_failure_count")).intValue();
    if (echecsCourants >= MAX_ECHECS_TOTP) {
      return ResultatTotp.dejaVerrouille();
    }

    String secret = (String) utilisateur.get("two_factor_secret");
    if (totpService.verifierCode(secret, code)) {
      jdbc.update("UPDATE users SET totp_failure_count = 0 WHERE id = :id", Map.of("id", userId));
      logAdminService.logAdmin(
          "info",
          "auth",
          "TOTP validé : " + identifiant,
          Map.of("identifiant", identifiant, "ip", ip));
      return ResultatTotp.succes();
    }

    int nouveauxEchecs = echecsCourants + 1;
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
