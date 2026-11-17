/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lecture et écriture de la configuration chiffrée de {@code app_config},
 * partagées par le web (qui y ajoute un cache) et le worker.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class ConfigChiffree {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigChiffree.class);

  private final ConfigCipher cipher;
  private final AccesAppConfig acces;

  /**
   * @param cipher chiffrement des valeurs
   * @param acces accès à la table {@code app_config}
   */
  public ConfigChiffree(final ConfigCipher cipher, final AccesAppConfig acces) {
    this.cipher = cipher;
    this.acces = acces;
  }

  /**
   * Lit et déchiffre une valeur.
   *
   * @param cle clé de configuration
   * @return la valeur en clair, vide si la clé est absente ou illisible
   */
  public Optional<String> lire(final String cle) {
    final Optional<String> chiffree = acces.lire(cle);
    if (chiffree.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(cipher.dechiffrer(chiffree.get()));
    } catch (final RuntimeException e) {
      LOG.error("Échec déchiffrement config '{}' — APP_CONFIG_SECRET modifié ?", cle, e);
      return Optional.empty();
    }
  }

  /**
   * Chiffre et écrit une valeur ; une valeur vide supprime la clé.
   *
   * @param cle clé de configuration
   * @param valeur valeur en clair, ou null ou vide pour supprimer
   */
  public void ecrire(final String cle, final String valeur) {
    if (valeur == null || valeur.isBlank()) {
      acces.supprimer(cle);
      LOG.info("config supprimée cle={}", cle);
      return;
    }
    acces.ecrire(cle, cipher.chiffrer(valeur));
  }

  /**
   * Liste les clés présentes en base.
   *
   * @return les clés configurées
   */
  public Set<String> cles() {
    return acces.cles();
  }
}
