/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;

/**
 * Identifiant de lieu Eventim ({@code internal:<nom>@<code postal>}) : sans
 * identifiant stable chez Eventim, le code postal distingue les homonymes.
 * La forme historique sans code postal reste reconnue (lieu saisi en admin).
 * @since 3.0.2
 * @author Kwaite
 */
public final class IdentifiantLieuEventim {

  /** Préfixe commun des identifiants de lieu Eventim. */
  public static final String PREFIXE = "internal:";

  private static final char SEPARATEUR_CODE_POSTAL = '@';

  private IdentifiantLieuEventim() {}

  /**
   * @param nomNormalise nom normalisé ({@link Normalisation#normaliserTexte})
   * @param codePostal code postal publié, peut être null
   * @return l'identifiant, qualifié par le code postal s'il est connu
   */
  public static String calculer(final String nomNormalise, final String codePostal) {
    Objects.requireNonNull(nomNormalise, "nomNormalise");
    final String cp = new PositionLieu(null, null, codePostal).codePostal();
    return cp != null
        ? PREFIXE + nomNormalise + SEPARATEUR_CODE_POSTAL + cp
        : PREFIXE + nomNormalise;
  }

  /**
   * @param identifiant identifiant de lieu, qualifié ou non
   * @return la forme historique (sans code postal) du même identifiant
   */
  public static String historique(final String identifiant) {
    final int separateur = identifiant.lastIndexOf(SEPARATEUR_CODE_POSTAL);
    return separateur > 0 ? identifiant.substring(0, separateur) : identifiant;
  }

  /**
   * @param identifiant identifiant de lieu, qualifié ou non
   * @return le nom normalisé du lieu, sans préfixe ni code postal
   */
  public static String nom(final String identifiant) {
    final String sansCodePostal = historique(identifiant);
    return sansCodePostal.startsWith(PREFIXE)
        ? sansCodePostal.substring(PREFIXE.length())
        : sansCodePostal;
  }
}
