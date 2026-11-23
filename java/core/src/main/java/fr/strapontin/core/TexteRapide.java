/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Parcours de caractères qui évitent les expressions régulières de
 * {@link Normalisation} quand le texte s'y prête ; la sortie est celle de
 * l'écriture par règles, vérifiée sur des noms réels.
 *
 * @author Kwaite
 */
final class TexteRapide {

  private static final int ASCII_MAX = 127;
  private static final int DECALAGE_MINUSCULE = 'a' - 'A';

  private TexteRapide() {}

  /* package-private : utilisé par Normalisation (même package).
   * Minuscules et chiffres séparés par des espaces simples, sans espace en
   * tête ni en queue : la normalisation n'y change rien. */
  static boolean estDejaNormalise(final String texte) {
    final int longueur = texte.length();
    char precedent = ' ';
    for (int indice = 0; indice < longueur; indice++) {
      final char caractere = texte.charAt(indice);
      if (!estMinusculeOuChiffre(caractere) && (caractere != ' ' || precedent == ' ')) {
        return false;
      }
      precedent = caractere;
    }
    return longueur == 0 || precedent != ' ';
  }

  /* package-private : utilisé par Normalisation (même package).
   * NFD laisse ces caractères tels quels et la suite les supprimerait :
   * remplacés avant NFD. */
  static String substituerNonDecomposables(final String texte) {
    final int longueur = texte.length();
    int indice = 0;
    while (indice < longueur && substitut(texte.charAt(indice)) == null) {
      indice++;
    }
    if (indice == longueur) {
      return texte;
    }
    final StringBuilder resultat = new StringBuilder(longueur + 4).append(texte, 0, indice);
    for (; indice < longueur; indice++) {
      final char caractere = texte.charAt(indice);
      final String remplacement = substitut(caractere);
      if (remplacement == null) {
        resultat.append(caractere);
      } else {
        resultat.append(remplacement);
      }
    }
    return resultat.toString();
  }

  /* package-private : utilisé par Normalisation (même package).
   * Clé d'un nom ASCII en une passe ; null si un caractère n'est pas ASCII. */
  static String cleDepuisAscii(final String nom) {
    final int longueur = nom.length();
    final StringBuilder cle = new StringBuilder(longueur);
    for (int indice = 0; indice < longueur; indice++) {
      final char caractere = nom.charAt(indice);
      if (caractere > ASCII_MAX) {
        return null;
      }
      if (caractere >= 'A' && caractere <= 'Z') {
        cle.append((char) (caractere + DECALAGE_MINUSCULE));
      } else if (estMinusculeOuChiffre(caractere)) {
        cle.append(caractere);
      }
    }
    return cle.toString();
  }

  private static boolean estMinusculeOuChiffre(final char caractere) {
    return (caractere >= 'a' && caractere <= 'z') || (caractere >= '0' && caractere <= '9');
  }

  private static String substitut(final char caractere) {
    return switch (caractere) {
      case 'ø', 'Ø' -> "o";
      case 'ł', 'Ł' -> "l";
      case 'đ', 'Đ' -> "d";
      case 'ŋ', 'Ŋ' -> "n";
      default -> null;
    };
  }
}
