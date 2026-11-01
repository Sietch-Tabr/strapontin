/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.eventim;

import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.PlateauSoiree;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Autres artistes d'une soirée Eventim. Eventim attache les têtes d'affiche
 * d'un festival à toutes ses soirées : seule une attraction que la partie
 * artistes du titre nomme y joue (« Sébastien Tellier + Yael Naim + Dressed
 * like boys - Les Rendez-Vous Soniques #17 »).
 * @since 3.0.4
 * @author Kwaite
 */
final class InvitesEventim {

  private static final String CLE_NOM = "name";

  private InvitesEventim() {}

  /**
   * @param titre titre du produit Eventim
   * @param attractions attractions du produit, peut être null
   * @param artistePrincipal artiste retenu pour le concert
   * @return autres artistes nommés par le titre, dans l'ordre des attractions
   */
  /* package-private : appelé par MapeurProduitEventim (même package). */
  static List<String> depuis(
      final String titre, final Object attractions, final String artistePrincipal) {
    if (titre == null || !(attractions instanceof List<?> liste)) {
      return List.of();
    }
    final String partie =
        " " + Normalisation.normaliserTexte(MapeurProduitEventim.extraireNomArtiste(titre)) + " ";
    final List<String> noms = new ArrayList<>();
    for (final Object attraction : liste) {
      if (attraction instanceof Map<?, ?> donnees && donnees.get(CLE_NOM) instanceof String nom) {
        final String norm = Normalisation.normaliserTexte(nom);
        if (!norm.isEmpty() && partie.contains(" " + norm + " ")) {
          noms.add(nom.strip());
        }
      }
    }
    return PlateauSoiree.sansArtistePrincipal(noms, artistePrincipal);
  }
}
