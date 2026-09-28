/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.config;

import fr.strapontin.core.ConfigCipher;
import fr.strapontin.web.admin.LogAdminService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rotation de {@code APP_CONFIG_SECRET} : au démarrage, rechiffre avec le
 * secret courant toute valeur encore chiffrée avec le secret précédent
 * ({@code APP_CONFIG_SECRET_PRECEDENT}) — {@code app_config} et secrets TOTP
 * des comptes. Idempotent : une valeur déjà rechiffrée (ou stockée en clair)
 * ne se déchiffre pas avec le secret précédent et reste inchangée. Procédure
 * complète : {@code scripts/rotation-secrets.sh}, wiki Sécurité.
 *
 * @since 3.0.1
 * @author Kwaite
 */
@Component
public class RechiffrementSecretConfig implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RechiffrementSecretConfig.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;
  private final String secret;
  private final String secretPrecedent;

  /**
   * @param jdbc accès JDBC
   * @param logAdminService journalisation du rechiffrement
   * @param secret secret courant {@code APP_CONFIG_SECRET}
   * @param secretPrecedent secret remplacé, vide hors rotation
   */
  public RechiffrementSecretConfig(
      final NamedParameterJdbcTemplate jdbc,
      final LogAdminService logAdminService,
      @Value("${app.config.secret}") final String secret,
      @Value("${app.config.secret-precedent:}") final String secretPrecedent) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
    this.secret = secret;
    this.secretPrecedent = secretPrecedent;
  }

  @Override
  @Transactional
  public void run(final ApplicationArguments args) {
    if (secretPrecedent == null || secretPrecedent.isBlank() || secretPrecedent.equals(secret)) {
      return;
    }
    final ConfigCipher ancien = new ConfigCipher(secretPrecedent);
    final ConfigCipher nouveau = new ConfigCipher(secret);
    final int nbConfig =
        rechiffrer(
            "SELECT cle AS id, valeur_chiffree AS valeur FROM app_config",
            "UPDATE app_config SET valeur_chiffree = :valeur WHERE cle = :id",
            ancien,
            nouveau);
    final int nbTotp =
        rechiffrer(
            "SELECT id, two_factor_secret AS valeur FROM users WHERE two_factor_secret IS NOT NULL",
            "UPDATE users SET two_factor_secret = :valeur WHERE id = :id",
            ancien,
            nouveau);
    LOG.info("rechiffrement APP_CONFIG_SECRET terminé app_config={} totp={}", nbConfig, nbTotp);
    logAdminService.logAdmin(
        "info",
        "securite",
        "rotation APP_CONFIG_SECRET : valeurs rechiffrées",
        Map.of("appConfig", nbConfig, "totp", nbTotp));
  }

  private int rechiffrer(
      final String selection,
      final String miseAJour,
      final ConfigCipher ancien,
      final ConfigCipher nouveau) {
    final List<Map<String, Object>> lignes = jdbc.queryForList(selection, Map.of());
    int rechiffrees = 0;
    for (final Map<String, Object> ligne : lignes) {
      final String clair = dechiffrerOuNull(ancien, (String) ligne.get("valeur"));
      if (clair != null) {
        jdbc.update(miseAJour, Map.of("id", ligne.get("id"), "valeur", nouveau.chiffrer(clair)));
        rechiffrees++;
      }
    }
    return rechiffrees;
  }

  // Une valeur déjà rechiffrée ou stockée en clair (quota JSON, secret TOTP
  // antérieur au chiffrement) ne se déchiffre pas avec l'ancien secret.
  private static String dechiffrerOuNull(final ConfigCipher ancien, final String valeur) {
    try {
      return ancien.dechiffrer(valeur);
    } catch (IllegalStateException | IllegalArgumentException e) {
      return null;
    }
  }
}
