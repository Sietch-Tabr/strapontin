/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Lecture et écriture du compteur d'envois Resend dans {@code app_config},
 * partagées par les gestionnaires de quota du web et du worker (les règles
 * restent dans {@link RegleQuotaResend}).
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class StockageCompteurResend {

  private static final Logger LOG = LoggerFactory.getLogger(StockageCompteurResend.class);

  private final AccesAppConfig acces;
  private final ObjectMapper objectMapper;

  /**
   * @param acces accès à la table {@code app_config}
   * @param objectMapper sérialisation du compteur en JSON
   */
  public StockageCompteurResend(final AccesAppConfig acces, final ObjectMapper objectMapper) {
    this.acces = acces;
    this.objectMapper = objectMapper;
  }

  /**
   * Lit le compteur tel que stocké, sans rollover.
   *
   * @return le compteur, vide s'il est absent ou malformé
   */
  public Optional<CompteurEnvoisResend> lireBrut() {
    try {
      return acces
          .lire(RegleQuotaResend.CLE_QUOTA)
          .map(json -> objectMapper.readValue(json, CompteurEnvoisResend.class));
    } catch (final JacksonException e) {
      LOG.warn("compteur Resend malformé en base — ignoré : {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Écrit le compteur ; une erreur de sérialisation est journalisée sans remonter.
   *
   * @param compteur compteur à stocker
   */
  public void ecrire(final CompteurEnvoisResend compteur) {
    try {
      acces.ecrire(RegleQuotaResend.CLE_QUOTA, objectMapper.writeValueAsString(compteur));
    } catch (final JacksonException e) {
      LOG.error("impossible de sérialiser le compteur Resend", e);
    }
  }
}
