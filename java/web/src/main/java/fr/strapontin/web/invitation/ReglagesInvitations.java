/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.invitation;

import fr.strapontin.web.config.ConfigService;
import org.springframework.stereotype.Component;

/**
 * Valeurs réglables, sans redéploiement, des invitations envoyées par les utilisateurs : une
 * seule définition pour le service, l'administration et les tests.
 * @since 3.1.4
 * @author Kwaite
 */
@Component
public class ReglagesInvitations {

  /** Clé {@code app_config} du plafond d'invitations en attente d'un utilisateur. */
  public static final String CLE_PLAFOND_DEFAUT = "INVITATIONS_PLAFOND_DEFAUT";

  /** Clé {@code app_config} du nombre d'envois par heure et par utilisateur. */
  public static final String CLE_ENVOIS_PAR_HEURE = "INVITATIONS_ENVOIS_PAR_HEURE";

  /** Clé {@code app_config} du nombre d'envois par jour, tous utilisateurs confondus. */
  public static final String CLE_ENVOIS_PAR_JOUR = "INVITATIONS_ENVOIS_PAR_JOUR";

  /** Plafond d'invitations en attente d'un utilisateur sans réglage propre. */
  public static final int PLAFOND_DEFAUT = 2;

  /** Valeur maximale acceptée pour un plafond. */
  public static final int PLAFOND_MAX = 50;

  private static final int ENVOIS_PAR_HEURE_DEFAUT = 5;
  // Une invitation est un email de 3 000 par mois : 30 par jour laisse l'essentiel au digest.
  private static final int ENVOIS_PAR_JOUR_DEFAUT = 30;

  private final ConfigService configService;

  /**
   * @param configService accès à {@code app_config}
   */
  public ReglagesInvitations(final ConfigService configService) {
    this.configService = configService;
  }

  /**
   * @return plafond d'invitations en attente d'un utilisateur sans réglage propre
   */
  public int plafondDefaut() {
    return lire(CLE_PLAFOND_DEFAUT, PLAFOND_DEFAUT, 0);
  }

  /**
   * @return nombre d'envois d'invitation autorisés par heure et par utilisateur
   */
  public int envoisParHeure() {
    return lire(CLE_ENVOIS_PAR_HEURE, ENVOIS_PAR_HEURE_DEFAUT, 1);
  }

  /**
   * @return nombre d'envois d'invitation autorisés par jour, tous utilisateurs confondus
   */
  public int envoisParJour() {
    return lire(CLE_ENVOIS_PAR_JOUR, ENVOIS_PAR_JOUR_DEFAUT, 1);
  }

  /**
   * Enregistre le plafond par défaut.
   * @param valeur plafond, entre 0 (invitation coupée) et {@link #PLAFOND_MAX}
   */
  public void definirPlafondDefaut(final int valeur) {
    configService.sauvegarderConfig(CLE_PLAFOND_DEFAUT, Integer.toString(valeur));
  }

  private int lire(final String cle, final int defaut, final int minimum) {
    return configService
        .lireConfig(cle)
        .map(
            valeur -> {
              try {
                return Math.max(minimum, Integer.parseInt(valeur.strip()));
              } catch (NumberFormatException e) {
                return defaut;
              }
            })
        .orElse(defaut);
  }
}
