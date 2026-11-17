/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fichier SHA256SUMS d'une release : une empreinte et un nom par ligne,
 * lues strictement (ligne mal formée ou nom en double refusés).
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class SommesControle {

  private static final Pattern LIGNE =
      Pattern.compile("([0-9a-f]{64}) [ *]([A-Za-z0-9][A-Za-z0-9._-]*)");

  private final Map<String, String> sommes;

  private SommesControle(final Map<String, String> sommes) {
    this.sommes = Map.copyOf(sommes);
  }

  /**
   * Lit le contenu d'un fichier SHA256SUMS.
   *
   * @param contenu texte du fichier
   * @return les empreintes par nom de fichier
   * @throws IntegriteException si une ligne est mal formée ou répétée
   */
  public static SommesControle analyser(final String contenu) {
    final Map<String, String> sommes = new HashMap<>();
    for (final String ligne : contenu.split("\n")) {
      if (ligne.isBlank()) {
        continue;
      }
      final Matcher correspondance = LIGNE.matcher(ligne);
      if (!correspondance.matches()) {
        throw new IntegriteException("Ligne illisible dans SHA256SUMS");
      }
      if (sommes.put(correspondance.group(2), correspondance.group(1)) != null) {
        throw new IntegriteException(
            "Fichier en double dans SHA256SUMS : " + correspondance.group(2));
      }
    }
    return new SommesControle(sommes);
  }

  /**
   * Contrôle l'empreinte d'un fichier téléchargé.
   *
   * @param telechargement fichier et empreinte calculée
   * @throws IntegriteException si le fichier est absent de la liste ou diffère
   */
  public void verifier(final Telechargement telechargement) {
    final String nom = telechargement.fichier().getFileName().toString();
    final String attendue = sommes.get(nom);
    if (attendue == null) {
      throw new IntegriteException("Fichier absent de SHA256SUMS : " + nom);
    }
    if (!attendue.equals(telechargement.sha256())) {
      throw new IntegriteException("Empreinte différente de SHA256SUMS : " + nom);
    }
  }
}
