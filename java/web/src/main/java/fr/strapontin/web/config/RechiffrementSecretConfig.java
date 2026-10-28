/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.config;

import fr.strapontin.core.ConfigCipher;
import fr.strapontin.web.admin.LogAdminService;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rechiffre au démarrage, avec le secret courant, les valeurs encore
 * chiffrées avec {@code APP_CONFIG_SECRET_PRECEDENT} (app_config, TOTP).
 * Chiffre aussi tout secret TOTP resté en clair. Idempotent. Procédure :
 * {@code scripts/rotation-secrets.sh}.
 * @since 3.0.1
 * @author Kwaite
 */
@Component
public class RechiffrementSecretConfig implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RechiffrementSecretConfig.class);

  private static final String CLE_VALEUR = "valeur";
  private static final Pattern SECRET_BASE32 = Pattern.compile("[A-Z2-7]{16,}=*");

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
    chiffrerSecretsTotpEnClair();
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

  // Un secret TOTP antérieur au chiffrement est un Base32 brut, jamais un
  // base64 de 28 octets ou plus : le motif le distingue sans déchiffrer.
  private void chiffrerSecretsTotpEnClair() {
    final ConfigCipher courant = new ConfigCipher(secret);
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT id, two_factor_secret AS valeur FROM users WHERE two_factor_secret IS NOT NULL",
            Map.of());
    int chiffres = 0;
    for (final Map<String, Object> ligne : lignes) {
      final String brut = (String) ligne.get(CLE_VALEUR);
      if (SECRET_BASE32.matcher(brut).matches()) {
        jdbc.update(
            "UPDATE users SET two_factor_secret = :valeur WHERE id = :id",
            Map.of("id", ligne.get("id"), CLE_VALEUR, courant.chiffrer(brut)));
        chiffres++;
      }
    }
    if (chiffres > 0) {
      LOG.warn("secrets TOTP en clair chiffrés au démarrage : {}", chiffres);
      logAdminService.logAdmin(
          "warn",
          "securite",
          "secrets TOTP stockés en clair chiffrés au démarrage",
          Map.of("totp", chiffres));
    }
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
