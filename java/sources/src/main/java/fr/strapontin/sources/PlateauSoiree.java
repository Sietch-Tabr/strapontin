/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.Normalisation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Autres artistes d'une soirée partagée (festival, co-plateau). Eventim
 * attache les têtes d'affiche d'un festival à toutes ses soirées : seule une
 * attraction que la partie artistes du titre nomme y joue (« Sébastien
 * Tellier + Yael Naim + Dressed like boys - Les Rendez-Vous Soniques #17 »).
 * @since 3.0.4
 * @author Kwaite
 */
final class PlateauSoiree {

  private static final String CLE_NOM = "name";

  private PlateauSoiree() {}

  /**
   * @param titre titre du produit Eventim
   * @param attractions attractions du produit, peut être null
   * @param artistePrincipal artiste retenu pour le concert
   * @return autres artistes nommés par le titre, dans l'ordre des attractions
   */
  /* package-private : appelé par MapeurProduitEventim (même package). */
  static List<String> invitesEventim(
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
    return sansArtistePrincipal(noms, artistePrincipal);
  }

  /**
   * @param noms noms des artistes de la soirée, principal compris
   * @param artistePrincipal artiste retenu pour le concert
   * @return les autres, sans doublon
   */
  /* package-private : appelé par les mappeurs du même package. */
  static List<String> sansArtistePrincipal(final List<String> noms, final String artistePrincipal) {
    final String principal =
        artistePrincipal != null ? Normalisation.normaliserTexte(artistePrincipal) : "";
    final List<String> invites = new ArrayList<>();
    final List<String> vus = new ArrayList<>();
    for (final String nom : noms) {
      final String norm = Normalisation.normaliserTexte(nom);
      if (norm.isEmpty()
          || CorrespondanceArtiste.estDoublonArtiste(norm, principal)
          || vus.contains(norm)) {
        continue;
      }
      vus.add(norm);
      invites.add(nom);
    }
    return invites;
  }
}
