/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.eventim;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import java.util.Map;

/**
 * Un titre Eventim qui n'est que le nom du lieu n'est pas un artiste.
 * @since 3.1.4
 * @author Kwaite
 */
final class TitreDuLieu {

  private static final String CLE_NOM = "name";

  private TitreDuLieu() {}

  // Un titre qui n'est que le nom du lieu (« Eglise St Germain des Prés »)
  // n'est pas un artiste : la première attraction du produit (l'ensemble qui
  // joue) le remplace quand elle existe.
  /* package-private : appelé par MapeurProduitEventim (même package). */
  static String artisteHorsNomDuLieu(
      final String titre, final Map<String, Object> location, final Map<String, Object> produit) {
    if (location == null
        || !(location.get(CLE_NOM) instanceof String lieu)
        || !CorrespondanceSalle.estDoublonSalle(
            Normalisation.normaliserTexte(titre), Normalisation.normaliserTexte(lieu))) {
      return titre;
    }
    if (produit.get("attractions") instanceof Iterable<?> attractions) {
      for (final Object attraction : attractions) {
        if (attraction instanceof Map<?, ?> fiche
            && fiche.get(CLE_NOM) instanceof String nom
            && !nom.isBlank()) {
          return nom.strip();
        }
      }
    }
    return titre;
  }
}
