/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Classement publié par une source : classification (segment), genre et
 * sous-genre ; chaque champ peut être null.
 * @since 3.0.2
 * @author Kwaite
 */
public record Classement(String classification, String genre, String sousGenre) {

  /** Aucun classement connu. */
  public static final Classement INCONNU = new Classement(null, null, null);

  /**
   * Clé de comparaison des trois niveaux, identique à celle calculée en SQL
   * sur {@code evenements} : {@code classification|genre|sous_genre}, valeur
   * absente remplacée par une chaîne vide.
   * @return la clé du classement
   */
  public String cle() {
    return valeur(classification) + "|" + valeur(genre) + "|" + valeur(sousGenre);
  }

  private static String valeur(final String champ) {
    return champ != null ? champ : "";
  }
}
