/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import org.springframework.stereotype.Service;

/**
 * Point d'entrée des fusions d'événements de la réconciliation nocturne : les
 * services de fusion par artiste, par produit Eventim et par séance en un seul
 * collaborateur, dans l'ordre où la nuit les enchaîne.
 * @since 3.1.2
 * @author Kwaite
 */
@Service
public class FusionsNocturnes {

  private final FusionEvenementsArtisteService parArtiste;
  private final FusionProduitsEventimService parProduit;
  private final FusionSeancesDeuxSourcesService parSeance;

  /**
   * @param parArtiste fusions par artiste, salle et jour
   * @param parProduit fusions par produit Eventim
   * @param parSeance fusions des séances publiées par deux billetteries
   */
  public FusionsNocturnes(
      final FusionEvenementsArtisteService parArtiste,
      final FusionProduitsEventimService parProduit,
      final FusionSeancesDeuxSourcesService parSeance) {
    this.parArtiste = parArtiste;
    this.parProduit = parProduit;
    this.parSeance = parSeance;
  }

  /**
   * @return nombre de fiches d'origine d'un report passées indisponibles
   */
  public int marquerFichesDOrigineDesReports() {
    return parProduit.marquerFichesDOrigineDesReports();
  }

  /**
   * Fusions de fiches périmées, de séances déplacées, republiées ou sans heure.
   * @return nombre de fusions effectuées
   */
  public int fusionnerFichesPerimeesEtSeances() {
    return parProduit.retirerObservationsDeProduitReattribue()
        + parProduit.fusionner()
        + parProduit.fusionnerProduitsRepublies()
        + parProduit.fusionnerProduitsRepubliesAilleurs()
        + parArtiste.fusionnerFichesPerimees()
        + parArtiste.fusionnerSeancesHeureChangee()
        + parArtiste.fusionnerMemeSeanceDeuxSourcesHeuresProches()
        + parSeance.fusionnerSeancesSansHeure()
        + parArtiste.fusionnerConcertsDeplaces();
  }

  /**
   * @return nombre d'événements fusionnés d'un même artiste, salle et jour
   */
  public int fusionnerEvenements() {
    return parArtiste.fusionner();
  }

  /**
   * @return nombre de spectacles publiés sous deux intitulés fusionnés
   */
  public int fusionnerMemeCreneauDeuxSources() {
    return parSeance.fusionnerMemeCreneauDeuxSources()
        + parSeance.fusionnerVariantesDeTitreEventim();
  }
}
