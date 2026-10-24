/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifiant numérique d'un produit Eventim, porté en fin d'adresse. Le reste
 * de l'adresse reprend le titre et change avec lui : seul cet identifiant
 * reconnaît un même produit après un changement de titre.
 * @since 3.1.0
 * @author Kwaite
 */
public final class ProduitEventim {

  private static final Pattern MOTIF_IDENTIFIANT = Pattern.compile("-([0-9]+)/?$");

  private ProduitEventim() {}

  /**
   * Expression SQL de l'identifiant, identique à celle de l'index des observations.
   * @param alias alias de la table {@code observations}
   * @return l'expression à employer telle quelle pour profiter de l'index
   */
  public static String expressionSql(final String alias) {
    return "substring(" + alias + ".url FROM '-([0-9]+)/?$')";
  }

  /**
   * @param url adresse d'un produit Eventim, éventuellement {@code null}
   * @return l'identifiant du produit, vide si l'adresse n'en porte pas
   */
  public static Optional<String> identifiant(final String url) {
    if (url == null) {
      return Optional.empty();
    }
    final Matcher correspondance = MOTIF_IDENTIFIANT.matcher(url);
    return correspondance.find() ? Optional.of(correspondance.group(1)) : Optional.empty();
  }
}
