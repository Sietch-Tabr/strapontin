/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ProduitEventim;
import java.util.List;

/**
 * Produits Eventim d'un lot de concerts : un produit publié plusieurs fois dans
 * le lot désigne des séances d'une série, jamais un produit dont la date ou
 * l'heure a changé.
 * @since 3.1.4
 * @author Kwaite
 */
final class ProduitsDuLot {

  private ProduitsDuLot() {}

  private static String cle(final ValeurConcert concert) {
    return "eventim".equals(concert.source())
        ? ProduitEventim.identifiant(concert.url()).orElse("")
        : "";
  }

  /**
   * @param lot concerts du lot en cours de persistance
   * @param concert concert du lot
   * @return {@code true} si son produit n'est publié qu'une fois dans le lot
   */
  /* package-private : appelé par RechercheEvenementExistant (même package). */
  static boolean estUnique(final List<ValeurConcert> lot, final ValeurConcert concert) {
    final String cle = cle(concert);
    return cle.isEmpty() || lot.stream().filter(c -> cle.equals(cle(c))).count() <= 1;
  }
}
