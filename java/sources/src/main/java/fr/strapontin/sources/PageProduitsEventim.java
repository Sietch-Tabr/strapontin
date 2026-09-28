/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.util.List;

/**
 * Page de produits Eventim.
 *
 * @param concerts concerts de la page (après filtrage éventuel)
 * @param total total annoncé par l'API pour la requête
 * @param succes {@code false} si l'appel HTTP a échoué — distingue une page
 *     réellement vide (succès, 0 résultat) d'un appel qui n'a jamais abouti
 * @since 3.0.1
 * @author Kwaite
 */
record PageProduitsEventim(List<ConcertBrut> concerts, int total, boolean succes) {

  /* package-private : page légitimement vide (0 résultat, pas d'échec HTTP). */
  static final PageProduitsEventim VIDE = new PageProduitsEventim(List.of(), 0, true);

  /* package-private : appel HTTP en échec. */
  static final PageProduitsEventim ECHEC = new PageProduitsEventim(List.of(), 0, false);

  /* package-private : utilisé par EventimSource/EventimLieuxFetcher (même package). */
  boolean estVide() {
    return concerts.isEmpty();
  }
}
