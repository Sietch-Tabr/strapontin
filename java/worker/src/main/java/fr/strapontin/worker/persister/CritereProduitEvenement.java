/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ProduitEventim;

/**
 * Critère SQL qui retrouve, parmi les observations, celles du même produit
 * qu'un concert entrant : identifiant numérique Eventim, adresse d'une page de
 * site de salle ou d'un événement Ticketmaster.
 * @since 3.1.1
 * @author Kwaite
 */
final class CritereProduitEvenement {

  private CritereProduitEvenement() {}

  /**
   * @param concert concert entrant
   * @return condition sur l'alias {@code o}, ou null sans identifiant de
   *   produit
   */
  /* package-private : appelé par RechercheEvenementExistant. */
  static String condition(final ValeurConcert concert) {
    if ("eventim".equals(concert.source())
        && ProduitEventim.identifiant(concert.url()).isPresent()) {
      return "o.source = 'eventim' AND " + ProduitEventim.expressionSql("o") + " = :produit";
    }
    if (("site_salle".equals(concert.source()) || "ticketmaster".equals(concert.source()))
        && concert.url() != null) {
      return "o.source = CAST(:source AS source_concert) AND o.url = :url";
    }
    return null;
  }

  /**
   * Une production à séances multiples partage une adresse Ticketmaster :
   * plusieurs fiches déjà rattachées à la même adresse rendent le choix ambigu.
   * @param concert concert entrant
   * @param candidats nombre de fiches portant ce produit
   * @return {@code true} si aucune fiche ne peut être choisie
   */
  /* package-private : appelé par RechercheEvenementExistant. */
  static boolean choixAmbigu(final ValeurConcert concert, final int candidats) {
    return "ticketmaster".equals(concert.source()) && candidats != 1;
  }
}
