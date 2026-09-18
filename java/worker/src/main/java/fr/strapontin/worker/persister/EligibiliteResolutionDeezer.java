/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.util.Set;

/**
 * Détermine si un concert est éligible à la résolution de nom
 * canonique via Deezer (détail : CLAUDE.md, resoudreParUrl).
 * @since 2.7.11
 * @author Kwaite
 */
public final class EligibiliteResolutionDeezer {

  private static final Set<String> CLASSIFICATIONS_NON_MUSICALES =
      Set.of("theatre", "divers", "sports");
  private static final String SOURCE_EVENTIM = "eventim";

  private EligibiliteResolutionDeezer() {}

  /**
   * @param classification classification du concert, {@code null} si inconnue
   * @param source source du concert ({@code ticketmaster}, {@code eventim} ,
   *     {@code digitick} )
   * @return {@code true} si le concert est éligible à la résolution de nom
   *     canonique via Deezer
   */
  public static boolean estEligible(final String classification, final String source) {
    if (classification != null && CLASSIFICATIONS_NON_MUSICALES.contains(classification)) {
      return false;
    }
    return !SOURCE_EVENTIM.equals(source) || (classification != null && !classification.isBlank());
  }
}
