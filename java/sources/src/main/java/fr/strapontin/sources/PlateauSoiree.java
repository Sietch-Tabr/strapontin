/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.Normalisation;
import java.util.ArrayList;
import java.util.List;

/**
 * Autres artistes d'une soirée partagée (festival, co-plateau), commun aux
 * sources.
 * @since 3.0.4
 * @author Kwaite
 */
public final class PlateauSoiree {

  private PlateauSoiree() {}

  /**
   * @param noms noms des artistes de la soirée, principal compris
   * @param artistePrincipal artiste retenu pour le concert
   * @return les autres, sans doublon
   */
  public static List<String> sansArtistePrincipal(
      final List<String> noms, final String artistePrincipal) {
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
