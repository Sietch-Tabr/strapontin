/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Contrôle des entrées d'une archive avant son extraction : fichiers et
 * dossiers seulement, tous sous {@code exploitation/}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
final class EntreesArchive {

  /* package-private : ArchiveExploitation y cherche l'extraction. */
  static final String RACINE = "exploitation";

  private static final Pattern NOM_ENTREE = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*/?");

  private EntreesArchive() {}

  /**
   * Refuse toute entrée hors fichiers et dossiers de {@code exploitation/}.
   *
   * @param noms sortie de {@code tar -t}
   * @param details sortie de {@code tar -tv}
   * @throws InstallationException si une entrée est refusée
   */
  /* package-private : ArchiveExploitation contrôle avant d'extraire. */
  static void controler(final List<String> noms, final List<String> details) {
    if (noms.isEmpty() || noms.size() != details.size()) {
      throw new InstallationException("Archive d'exploitation vide ou incohérente");
    }
    for (int i = 0; i < noms.size(); i++) {
      if (!typeAutorise(details.get(i).charAt(0))) {
        throw new InstallationException("Entrée d'archive refusée (lien ou fichier spécial)");
      }
      if (!cheminAutorise(noms.get(i))) {
        throw new InstallationException("Entrée d'archive refusée (chemin suspect)");
      }
    }
  }

  private static boolean typeAutorise(final char type) {
    return type == '-' || type == 'd';
  }

  private static boolean cheminAutorise(final String nom) {
    if (!NOM_ENTREE.matcher(nom).matches() || nom.contains("//")) {
      return false;
    }
    final String sansFinal = nom.endsWith("/") ? nom.substring(0, nom.length() - 1) : nom;
    final boolean sousRacine = RACINE.equals(sansFinal) || sansFinal.startsWith(RACINE + "/");
    return sousRacine && !List.of(sansFinal.split("/")).contains("..");
  }
}
