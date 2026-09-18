/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Valeurs normalisées d'un concert prêtes pour l'upsert en base
 * (noms de champs auto-descriptifs ; {@code disponible} exclu,
 * colonne {@code GENERATED ALWAYS}).
 * @since 2.0.0
 * @author Kwaite
 */
public record ValeurConcert(
    String artiste,
    String artisteNorm,
    LocalDate date,
    LocalTime heure,
    String salle,
    String salleNorm,
    String ville,
    String dept,
    String url,
    String source,
    String classification,
    String genre,
    String statut,
    String presaleDebut,
    String presaleNom,
    String salleSourceId,
    String artisteSourceId,
    UUID salleId,
    UUID artisteId) {

  // Seul point de reconstruction du record — les méthodes avecXxx ci-dessous ne
  // varient que sur ces quatre champs (les seuls jamais modifiés après la
  // résolution des identités sources) ; l'ajout d'un futur champ au record ne
  // demande de mettre à jour que cette seule méthode.
  private ValeurConcert avec(
      final String artisteNorm, final String salleNorm, final UUID salleId, final UUID artisteId) {
    return new ValeurConcert(
        artiste,
        artisteNorm,
        date,
        heure,
        salle,
        salleNorm,
        ville,
        dept,
        url,
        source,
        classification,
        genre,
        statut,
        presaleDebut,
        presaleNom,
        salleSourceId,
        artisteSourceId,
        salleId,
        artisteId);
  }

  /**
   * Retourne un nouveau concert avec le {@code salleNorm} remplacé.
   *
   * @param nouvelleSalleNorm nouveau nom de salle normalisé
   * @return nouveau record avec le champ modifié
   */
  public ValeurConcert avecSalleNorm(final String nouvelleSalleNorm) {
    return avec(artisteNorm, nouvelleSalleNorm, salleId, artisteId);
  }

  /**
   * Retourne un nouveau concert avec le {@code salleId} remplacé.
   *
   * @param nouvelleSalleId nouveau UUID de salle
   * @return nouveau record avec le champ modifié
   */
  public ValeurConcert avecSalleId(final UUID nouvelleSalleId) {
    return avec(artisteNorm, salleNorm, nouvelleSalleId, artisteId);
  }

  /**
   * Retourne un nouveau concert avec l'{@code artisteNorm} remplacé.
   *
   * @param nouvelArtisteNorm nouveau nom d'artiste normalisé
   * @return nouveau record avec le champ modifié
   */
  public ValeurConcert avecArtisteNorm(final String nouvelArtisteNorm) {
    return avec(nouvelArtisteNorm, salleNorm, salleId, artisteId);
  }

  /**
   * Retourne un nouveau concert avec l'{@code artisteId} remplacé.
   *
   * @param nouvelArtisteId nouveau UUID d'artiste
   * @return nouveau record avec le champ modifié
   */
  public ValeurConcert avecArtisteId(final UUID nouvelArtisteId) {
    return avec(artisteNorm, salleNorm, salleId, nouvelArtisteId);
  }

  /**
   * Retourne un nouveau concert avec le {@code salleId} et l'{@code artisteId}
   * remplacés.
   *
   * @param nouvelleSalleId nouveau UUID de salle (null conserve l'existant)
   * @param nouvelArtisteId nouveau UUID d'artiste (null conserve l'existant)
   * @return nouveau record avec les champs modifiés
   */
  public ValeurConcert avecUuids(final UUID nouvelleSalleId, final UUID nouvelArtisteId) {
    return avec(artisteNorm, salleNorm, nouvelleSalleId, nouvelArtisteId);
  }

  /**
   * Retourne un nouveau concert avec l'{@code artisteNorm} et l'{@code
   * artisteId} remplacés.
   *
   * @param nouvelArtisteNorm nouveau nom d'artiste normalisé
   * @param nouvelArtisteId nouveau UUID d'artiste
   * @return nouveau record avec les champs modifiés
   */
  public ValeurConcert avecArtisteNormEtId(
      final String nouvelArtisteNorm, final UUID nouvelArtisteId) {
    return avec(nouvelArtisteNorm, salleNorm, salleId, nouvelArtisteId);
  }
}
