/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.core.AccesAppConfig;
import fr.strapontin.core.ConfigChiffree;
import fr.strapontin.core.ConfigCipher;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service de configuration chiffrée — format de chiffrement détaillé :
 * CLAUDE.md, section Configuration applicative. Valeurs mises en cache
 * Caffeine 5 min, {@link #sauvegarderConfig} invalide immédiatement le
 * cache de la clé modifiée.
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class ConfigService {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigService.class);
  private static final String CLE_CLE = "cle";

  private final ConfigChiffree config;
  private final Cache<String, Optional<String>> cache;

  /**
   * @param secret secret de chiffrement {@code APP_CONFIG_SECRET}
   * @param secretPrecedent secret remplacé lors d'une rotation en cours, vide sinon
   * @param jdbc accès JDBC
   */
  public ConfigService(
      @Value("${app.config.secret}") final String secret,
      @Value("${app.config.secret-precedent:}") final String secretPrecedent,
      final NamedParameterJdbcTemplate jdbc) {
    this.config =
        new ConfigChiffree(new ConfigCipher(secret, secretPrecedent), new AccesAppConfig(jdbc));
    this.cache =
        Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(200).build();
  }

  /**
   * Lit une valeur de configuration depuis le cache ou la base de données.
   *
   * @param cle la clé de configuration
   * @return la valeur déchiffrée, ou vide si la clé n'existe pas
   */
  public Optional<String> lireConfig(final String cle) {
    Objects.requireNonNull(cle, "La clé de configuration est requise");
    return cache.get(cle, this::chargerDepuisDb);
  }

  /**
   * Sauvegarde une valeur de configuration chiffrée en base de données.
   *
   * <p>Si {@code valeur} est vide, la clé est supprimée.
   *
   * @param cle la clé de configuration
   * @param valeur la valeur en clair, ou {@code null} pour supprimer
   */
  public void sauvegarderConfig(final String cle, final String valeur) {
    Objects.requireNonNull(cle, "La clé de configuration est requise");
    cache.invalidate(cle);
    config.ecrire(cle, valeur);
  }

  /**
   * Retourne l'ensemble des clés présentes en base de données.
   *
   * @return les clés configurées
   */
  public Set<String> lireConfigsPresentes() {
    return config.cles();
  }

  private Optional<String> chargerDepuisDb(final String cle) {
    return config.lire(cle);
  }
}
