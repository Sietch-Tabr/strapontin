/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lieux Eventim : relevé des lieux vus sur les produits (pour salle_sources)
 * et interrogation par lieu ou par villes d'une région.
 * @since 3.0.1
 * @author Kwaite
 */
final class LieuxEventim {

  private static final Logger LOG = LoggerFactory.getLogger(LieuxEventim.class);

  private final EventimLieuxFetcher fetcher;
  // Clé : identifiant interne ; vidé à chaque prélèvement par le worker.
  private final Map<String, LieuEventim> lieuxObserves = new ConcurrentHashMap<>();
  private final AtomicReference<FournisseurVillesEventim> fournisseurVilles =
      new AtomicReference<>(depts -> List.of());

  /* package-private : instancié par EventimSource (même package). */
  LieuxEventim(final EventimLieuxFetcher fetcher) {
    this.fetcher = fetcher;
  }

  /* package-private : appelé par EventimSource (même package). */
  void definirFournisseurVilles(final FournisseurVillesEventim fournisseur) {
    fournisseurVilles.set(Objects.requireNonNull(fournisseur, "fournisseur"));
  }

  /* package-private : appelé par EventimSource pour chaque produit reçu. */
  void relever(final Map<String, Object> produit) {
    final LieuEventim lieu = MapeurProduitEventim.extraireLieu(produit);
    if (lieu != null) {
      lieuxObserves.put(lieu.identifiant(), lieu);
    }
  }

  /* package-private : appelé par EventimSource (même package). */
  List<LieuEventim> preleverLieuxObserves() {
    final List<LieuEventim> lieux = new ArrayList<>();
    for (final String identifiant : List.copyOf(lieuxObserves.keySet())) {
      final LieuEventim lieu = lieuxObserves.remove(identifiant);
      if (lieu != null) {
        lieux.add(lieu);
      }
    }
    return lieux;
  }

  /**
   * Aucune ville connue pour ces départements : aucune garantie de
   * couverture (horizon null).
   */
  /* package-private : appelé par EventimSource (même package). */
  ResultatRegion fetchRegion(final List<String> depts, final List<String> categories) {
    final List<String> villes = fournisseurVilles.get().villes(depts);
    if (villes.isEmpty()) {
      LOG.info("Eventim fetchRegion depts={} : aucune ville connue", depts);
      return new ResultatRegion(List.of(), null);
    }
    final ResultatRegion resultat = fetcher.fetchVilles(villes, new HashSet<>(depts), categories);
    LOG.info(
        "Eventim fetchRegion depts={} villes={} total={} horizon={}",
        depts,
        villes.size(),
        resultat.concerts().size(),
        resultat.horizon());
    return resultat;
  }

  /* package-private : appelé par EventimSource ; sans libellé, aucune requête. */
  List<ConcertBrut> fetchLieu(final String libelle) {
    if (libelle == null || libelle.isBlank()) {
      return List.of();
    }
    final List<ConcertBrut> concerts = fetcher.fetchLieu(libelle);
    LOG.info("Eventim fetchSalle lieu={} total={}", libelle, concerts.size());
    return concerts;
  }

  /* package-private : appelé par EventimSource ; -1 si vide ou injoignable. */
  int compterConcerts(final String libelle) {
    if (libelle == null || libelle.isBlank()) {
      return -1;
    }
    try {
      return fetcher.compterConcerts(libelle.strip());
    } catch (RuntimeException e) {
      LOG.warn("Eventim injoignable pour le test du lieu : {}", e.getMessage());
      return -1;
    }
  }
}
