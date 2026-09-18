/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.regex.Pattern;

/**
 * Filtre de qualité sur les noms d'artiste, appliqué à la création dans le
 * référentiel.
 *
 * @since 2.6.1
 * @author Kwaite
 */
public final class FiltreArtiste {

  // Motifs "tribute/hommage" : une attraction TM/Eventim distincte de l'artiste
  // original, qui ne peut jamais être rapprochée d'une entité Wikidata
  // musicien/groupe et pollue le référentiel admin sans jamais accumuler de
  // concerts légitimes. UNICODE_CHARACTER_CLASS : sans ce flag, \b ne reconnaît
  // pas "à" comme caractère de mot (\w par défaut est ASCII-only), et le \b
  // final de "hommage à\b" ne matche jamais.
  private static final Pattern MOTIF_TRIBUTE =
      Pattern.compile(
          "\\b(a\\s+)?tribute\\s+to\\b|\\bhommage\\s+[aà]\\b|\\bcover\\s+of\\b",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

  private FiltreArtiste() {}

  /**
   * Indique si un nom d'artiste correspond à un motif tribute/hommage plutôt
   * qu'à l'artiste original (ex. {@code "Tribute to Nina Simone"} ,
   * {@code "Hommage à Piaf"} ).
   *
   * @param nom nom affiché tel que saisi ou reçu d'une source externe
   * @return {@code true} si le nom matche un motif tribute/hommage
   */
  public static boolean estTribute(final String nom) {
    return nom != null && MOTIF_TRIBUTE.matcher(nom).find();
  }
}
