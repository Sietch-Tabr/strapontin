/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Fichier joint à une release GitHub : nom sans chemin, adresse de
 * téléchargement et taille annoncée en octets.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public record Asset(String nom, URI url, long taille) {

  private static final Pattern NOM_VALIDE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  /** Refuse un nom qui pourrait sortir du dossier de travail. */
  public Asset {
    if (nom == null || !NOM_VALIDE.matcher(nom).matches() || nom.contains("..")) {
      throw new IllegalArgumentException("Nom de fichier de release invalide : " + nom);
    }
    if (url == null || taille < 0) {
      throw new IllegalArgumentException("Fichier de release invalide : " + nom);
    }
  }
}
