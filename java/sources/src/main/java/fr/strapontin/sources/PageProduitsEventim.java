/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.util.List;

/**
 * Page de produits Eventim — {@code succes} distingue une page réellement
 * vide d'un appel HTTP qui n'a jamais abouti ; {@code totalPages} borne la
 * pagination ({@code total} compte des produits qu'aucune page n'expose).
 * @since 3.0.1
 * @author Kwaite
 */
record PageProduitsEventim(
    List<ConcertBrut> concerts, int total, int totalPages, boolean succes) {

  /* package-private : page légitimement vide (0 résultat, pas d'échec HTTP). */
  static final PageProduitsEventim VIDE = new PageProduitsEventim(List.of(), 0, 0, true);

  /* package-private : appel HTTP en échec. */
  static final PageProduitsEventim ECHEC = new PageProduitsEventim(List.of(), 0, 0, false);

  /* package-private : utilisé par EventimSource/EventimLieuxFetcher (même package). */
  boolean estVide() {
    return concerts.isEmpty();
  }
}
