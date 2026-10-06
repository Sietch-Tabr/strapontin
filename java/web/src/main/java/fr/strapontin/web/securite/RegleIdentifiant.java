/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.ErreurMetier;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Règle unique de l'identifiant de connexion, appliquée au premier
 * démarrage, à l'activation d'une invitation et au changement
 * d'identifiant : mise en minuscules, puis longueur et format.
 * @since 3.0.3
 * @author Kwaite
 */
public final class RegleIdentifiant {

  /** Longueur minimale, en caractères. */
  public static final int LONGUEUR_MIN = 3;

  /** Longueur maximale, en caractères. */
  public static final int LONGUEUR_MAX = 30;

  /** Message d'erreur commun aux trois parcours. */
  public static final String MESSAGE =
      "L'identifiant doit contenir de 3 à 30 caractères : lettres, chiffres, points, tirets"
          + " ou tirets bas (_).";

  private static final Pattern FORMAT = Pattern.compile("[a-z0-9._-]+");

  private RegleIdentifiant() {}

  /**
   * @param brut identifiant saisi
   * @return l'identifiant normalisé (sans espaces autour, en minuscules)
   * @throws ErreurMetier si l'identifiant ne respecte pas la règle
   */
  public static String valider(final String brut) {
    final String identifiant = brut == null ? "" : brut.strip().toLowerCase(Locale.ROOT);
    if (identifiant.length() < LONGUEUR_MIN
        || identifiant.length() > LONGUEUR_MAX
        || !FORMAT.matcher(identifiant).matches()) {
      throw new ErreurMetier(MESSAGE);
    }
    return identifiant;
  }
}
