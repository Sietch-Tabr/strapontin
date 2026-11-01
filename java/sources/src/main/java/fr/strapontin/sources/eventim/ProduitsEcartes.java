/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.eventim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Filtre des récupérations par artiste d'Eventim et file de ses produits
 * écartés, prélevée par le worker.
 * @since 3.0.3
 * @author Kwaite
 */
public final class ProduitsEcartes implements SourceAvecProduitsEcartes {

  private final Queue<ProduitEcarte> file = new ConcurrentLinkedQueue<>();

  /* package-private : instancié par EventimSource (même package). */
  ProduitsEcartes() {
    // file vide
  }

  /**
   * Retient les produits qui concernent l'artiste ({@link ProduitsDeLArtiste})
   * et met les autres en file.
   * @param produits produits d'une récupération par artiste
   * @param artiste nom de l'artiste recherché, null pour tout garder
   * @return les produits retenus
   */
  /* package-private : appelé par EventimSource (même package). */
  List<Map<String, Object>> retenir(
      final List<Map<String, Object>> produits, final String artiste) {
    if (artiste == null) {
      return produits;
    }
    return ProduitsDeLArtiste.retenir(produits, artiste, produit -> ajouter(artiste, produit));
  }

  private void ajouter(final String artiste, final Map<String, Object> produit) {
    file.add(
        new ProduitEcarte(
            artiste,
            MapeurProduitEventim.extraireUrlProduit(produit),
            (String) produit.get("name")));
  }

  @Override
  public List<ProduitEcarte> preleverProduitsEcartes() {
    final List<ProduitEcarte> preleves = new ArrayList<>();
    for (ProduitEcarte produit = file.poll(); produit != null; produit = file.poll()) {
      preleves.add(produit);
    }
    return preleves;
  }
}
