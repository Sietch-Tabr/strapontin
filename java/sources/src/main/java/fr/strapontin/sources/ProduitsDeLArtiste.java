/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.Hommage;
import fr.strapontin.core.Normalisation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
   * @param ecartes reçoit chaque produit écarté
   * @return les produits qui ne sont pas la date d'un autre artiste
   */
  /* package-private : appelé par EventimSource (même package). */
  static List<Map<String, Object>> retenir(
      final List<Map<String, Object>> produits,
      final String artiste,
      final Consumer<Map<String, Object>> ecartes) {
    final Set<String> festivalsNommes = festivalsNommantLArtiste(produits, artiste);
    final List<Map<String, Object>> retenus = new ArrayList<>();
    for (final Map<String, Object> produit : produits) {
      if (concerneArtiste(produit, artiste)
          && !soireeDUnFestivalOuLArtisteANomme(produit, artiste, festivalsNommes)) {
        retenus.add(produit);
      } else {
        ecartes.accept(produit);
      }
    }
    if (retenus.size() < produits.size()) {
      LOG.info(
          "Eventim artiste={} : {} produit(s) d'un autre artiste du même festival écarté(s)",
          artiste,
          produits.size() - retenus.size());
    }
    return retenus;
  }

  /**
   * Festivals dont un produit nomme l'artiste (« Suzane - Les Rendez-vous
   * Soniques #17 ») : ses soirées titrées du seul nom du festival sont alors
   * celles d'autres artistes, Eventim rattachant l'artiste à tout le festival.
   * Sans produit nommant l'artiste, un titre de festival seul reste gardé (il
   * peut être sa propre date : Nancy Jazz Pulsations).
   */
  private static Set<String> festivalsNommantLArtiste(
      final List<Map<String, Object>> produits, final String artiste) {
    final String recherche = artiste == null ? "" : Normalisation.normaliserTexte(artiste);
    final Set<String> festivals = new HashSet<>();
    if (recherche.isEmpty()) {
      return festivals;
    }
    for (final Map<String, Object> produit : produits) {
      if (produit.get(CLE_NOM) instanceof String titre) {
        final String festival = festivalNommantLArtiste(titre, recherche);
        if (festival != null) {
          festivals.add(festival);
        }
      }
    }
    return festivals;
  }

  // « Suzane - Les Rendez-vous Soniques #17 » : festival nommé après l'artiste.
  private static String festivalNommantLArtiste(final String titre, final String recherche) {
    final String partie = MapeurProduitEventim.extraireNomArtiste(titre);
    if (partie.length() >= titre.length()
        || !(" " + Normalisation.normaliserTexte(partie) + " ").contains(" " + recherche + " ")) {
      return null;
    }
    final String suite =
        Normalisation.normaliserTexte(
            titre.substring(titre.indexOf(partie) + partie.length()).replace("..", ""));
    return suite.length() >= LONGUEUR_MIN_TRONQUE ? suite : null;
  }

  private static boolean soireeDUnFestivalOuLArtisteANomme(
      final Map<String, Object> produit, final String artiste, final Set<String> festivals) {
    if (festivals.isEmpty() || !(produit.get(CLE_NOM) instanceof String titre)) {
      return false;
    }
    final String partie =
        Normalisation.normaliserTexte(MapeurProduitEventim.extraireNomArtiste(titre));
    if ((" " + partie + " ").contains(" " + Normalisation.normaliserTexte(artiste) + " ")) {
      return false;
    }
    final String titreNormalise = Normalisation.normaliserTexte(titre);
    return festivals.stream()
        .anyMatch(festival -> titreNormalise.equals(festival) || titreNormalise.contains(festival));
  }

  /**
   * Faux pour « Autres artistes - Festival » sans l'artiste recherché, et pour
   * un hommage (« tribute ») à l'artiste recherché ; vrai si le titre nomme
   * l'événement lui-même (« Les Voix de Carmen »).
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
    if (Hommage.estHommage(titre) && !Hommage.estHommage(artiste)) {
      return false;
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
