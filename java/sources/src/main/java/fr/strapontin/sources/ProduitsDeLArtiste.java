/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.Normalisation;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produits Eventim d'une récupération par artiste qui le concernent bien :
 * Eventim attache les têtes d'affiche d'un festival à tous ses produits, y
 * compris aux dates d'autres artistes.
 * @since 3.0.2
 * @author Kwaite
 */
final class ProduitsDeLArtiste {

  private static final Logger LOG = LoggerFactory.getLogger(ProduitsDeLArtiste.class);
  private static final String CLE_NOM = "name";
  private static final int LONGUEUR_MIN_TRONQUE = 5;

  private ProduitsDeLArtiste() {}

  /**
   * @param produits produits renvoyés pour l'artiste
   * @param artiste nom de l'artiste recherché
   * @return les produits qui ne sont pas la date d'un autre artiste
   */
  /* package-private : appelé par EventimSource (même package). */
  static List<Map<String, Object>> retenir(
      final List<Map<String, Object>> produits, final String artiste) {
    final List<Map<String, Object>> retenus =
        produits.stream().filter(p -> concerneArtiste(p, artiste)).toList();
    if (retenus.size() < produits.size()) {
      LOG.info(
          "Eventim artiste={} : {} produit(s) d'un autre artiste du même festival écarté(s)",
          artiste,
          produits.size() - retenus.size());
    }
    return retenus;
  }

  /**
   * Faux pour « Autres artistes - Festival » sans l'artiste recherché ; vrai
   * si le titre nomme l'événement lui-même (« Les Voix de Carmen »).
   * @param produit produit Eventim sous forme de Map imbriquée
   * @param artiste nom de l'artiste recherché
   * @return {@code false} si le titre désigne un autre artiste
   */
  /* package-private : appelé par retenir() et les tests (même package). */
  static boolean concerneArtiste(final Map<String, Object> produit, final String artiste) {
    final String titre = (String) produit.get(CLE_NOM);
    if (titre == null || artiste == null) {
      return true;
    }
    final String partieArtiste =
        " " + Normalisation.normaliserTexte(MapeurProduitEventim.extraireNomArtiste(titre)) + " ";
    final String recherche = Normalisation.normaliserTexte(artiste);
    if (recherche.isEmpty() || partieArtiste.contains(" " + recherche + " ")) {
      return true;
    }
    return !(produit.get("attractions") instanceof List<?> attractions)
        || !nommeUnAutreArtiste(partieArtiste, recherche, attractions)
        || !suiteNommeUneAttraction(titre, recherche, attractions);
  }

  // Suite du titre après « - » ; Eventim tronque parfois (« Les Rendez.. »).
  private static boolean suiteNommeUneAttraction(
      final String titre, final String recherche, final List<?> attractions) {
    final String artiste = MapeurProduitEventim.extraireNomArtiste(titre);
    if (artiste.length() >= titre.length()) {
      return false;
    }
    final String suite =
        Normalisation.normaliserTexte(
            titre.substring(titre.indexOf(artiste) + artiste.length()).replace("..", ""));
    for (final Object attraction : attractions) {
      if (attraction instanceof Map<?, ?> donnees && donnees.get(CLE_NOM) instanceof String nom) {
        final String autre = Normalisation.normaliserTexte(nom);
        final boolean nommee =
            (" " + suite + " ").contains(" " + autre + " ")
                || suite.length() >= LONGUEUR_MIN_TRONQUE && autre.startsWith(suite);
        if (!autre.isEmpty() && !autre.equals(recherche) && nommee) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean nommeUnAutreArtiste(
      final String partieArtiste, final String recherche, final List<?> attractions) {
    for (final Object attraction : attractions) {
      if (attraction instanceof Map<?, ?> donnees && donnees.get(CLE_NOM) instanceof String nom) {
        final String autre = Normalisation.normaliserTexte(nom);
        final boolean memeArtiste = autre.contains(recherche) || recherche.contains(autre);
        if (!autre.isEmpty() && !memeArtiste && partieArtiste.contains(" " + autre + " ")) {
          return true;
        }
      }
    }
    return false;
  }
}
