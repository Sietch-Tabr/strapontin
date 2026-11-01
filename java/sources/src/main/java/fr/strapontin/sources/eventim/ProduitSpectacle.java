/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.eventim;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Distingue une séance de spectacle des autres produits qu'une requête par
 * lieu renvoie avec elle : visites, parcs de loisirs, matchs, parkings.
 * @since 3.1.2
 * @author Kwaite
 */
final class ProduitSpectacle {

  private static final String CLE_NOM = "name";
  private static final String PREFIXE_LIEU_PARKING = "PARKING";
  // Catégories racines d'un spectacle ; les visites, parcs et matchs n'en sont pas.
  private static final Set<String> CATEGORIES_SPECTACLE =
      Set.of("Concerts & Festivals", "Classique & Danse", "Théâtre", "Humour", "Spectacles");

  private ProduitSpectacle() {}

  /**
   * @param produit produit Eventim sous forme de Map imbriquée
   * @return {@code true} si le produit peut devenir une fiche de concert : ni
   *     un lieu de parking, ni une catégorie racine hors spectacle (un produit
   *     sans catégorie connue est gardé)
   */
  /* package-private : appelé par EventimSource (même package). */
  static boolean estSpectacle(final Map<String, Object> produit) {
    return !estLieuDeParking(produit) && aCategorieSpectacleOuInconnue(produit);
  }

  private static boolean estLieuDeParking(final Map<String, Object> produit) {
    return produit.get("typeAttributes") instanceof Map<?, ?> attributs
        && attributs.get("liveEntertainment") instanceof Map<?, ?> spectacle
        && spectacle.get("location") instanceof Map<?, ?> lieu
        && lieu.get(CLE_NOM) instanceof String nom
        && nom.strip().toUpperCase(Locale.ROOT).startsWith(PREFIXE_LIEU_PARKING);
  }

  private static boolean aCategorieSpectacleOuInconnue(final Map<String, Object> produit) {
    if (!(produit.get("categories") instanceof Iterable<?> categories)) {
      return true;
    }
    boolean racineConnue = false;
    for (final Object categorie : categories) {
      if (categorie instanceof Map<?, ?> fiche
          && !fiche.containsKey("parentCategory")
          && fiche.get(CLE_NOM) instanceof String nom) {
        racineConnue = true;
        if (CATEGORIES_SPECTACLE.contains(nom)) {
          return true;
        }
      }
    }
    return !racineConnue;
  }
}
