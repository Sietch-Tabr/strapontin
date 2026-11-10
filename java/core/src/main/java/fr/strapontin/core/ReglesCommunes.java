/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Règles de sécurité et de rapprochement qui doivent rester identiques partout où elles
 * s'appliquent : chacune est définie ici une seule fois, jamais redéclarée dans deux classes.
 * @since 3.1.4
 * @author Kwaite
 */
public final class ReglesCommunes {

  /** Longueur minimale d'un mot de passe, à la création du compte comme au changement. */
  public static final int LONGUEUR_MIN_MOT_DE_PASSE = 12;

  /** Écart d'heure toléré entre deux fiches Ticketmaster d'un même événement, en minutes. */
  public static final int TOLERANCE_HEURE_MINUTES = 3;

  /** Écart d'heure maximal d'une même séance publiée deux fois, en secondes. */
  public static final int ECART_MAX_HEURE_SECONDES = 3600;

  /** Rayon, en mètres, dans lequel deux lieux publiés désignent le même lieu. */
  public static final double RAYON_MEME_LIEU_METRES = 300;

  private ReglesCommunes() {}
}
