/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/// Valeurs normalisées d'un concert prêtes pour l'upsert en base.
///
/// La colonne `disponible` est exclue : elle est `GENERATED ALWAYS AS`
/// dans le schéma PostgreSQL et ne doit jamais être insérée ni mise à jour.
///
/// `salleSourceId` et `artisteSourceId` portent les identifiants externes préfixés
/// (ex. {@code "tm:rZFSnyZdFvA"}) utilisés par {@code PersisteurConcertsDb} pour la
/// résolution UUID-first via {@code salle_sources} / {@code artiste_sources}.
///
/// `salleId` et `artisteId` sont remplis par la résolution des identités sources ;
/// null si aucune correspondance trouvée.
///
/// @since 2.0.0
/// @author Kwaite
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

  /// Retourne un nouveau concert avec le {@code salleNorm} remplacé.
  ///
  /// @param nouvelleSalleNorm nouveau nom de salle normalisé
  /// @return nouveau record avec le champ modifié
  public ValeurConcert avecSalleNorm(String nouvelleSalleNorm) {
    return new ValeurConcert(
        artiste,
        artisteNorm,
        date,
        heure,
        salle,
        nouvelleSalleNorm,
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

  /// Retourne un nouveau concert avec le {@code salleId} remplacé.
  ///
  /// @param nouvelleSalleId nouveau UUID de salle
  /// @return nouveau record avec le champ modifié
  public ValeurConcert avecSalleId(UUID nouvelleSalleId) {
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
        nouvelleSalleId,
        artisteId);
  }

  /// Retourne un nouveau concert avec l'{@code artisteNorm} remplacé.
  ///
  /// @param nouvelArtisteNorm nouveau nom d'artiste normalisé
  /// @return nouveau record avec le champ modifié
  public ValeurConcert avecArtisteNorm(String nouvelArtisteNorm) {
    return new ValeurConcert(
        artiste,
        nouvelArtisteNorm,
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

  /// Retourne un nouveau concert avec l'{@code artisteId} remplacé.
  ///
  /// @param nouvelArtisteId nouveau UUID d'artiste
  /// @return nouveau record avec le champ modifié
  public ValeurConcert avecArtisteId(UUID nouvelArtisteId) {
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
        nouvelArtisteId);
  }

  /// Retourne un nouveau concert avec le {@code salleId} et l'{@code artisteId} remplacés.
  ///
  /// @param nouvelleSalleId nouveau UUID de salle (null conserve l'existant)
  /// @param nouvelArtisteId   nouveau UUID d'artiste (null conserve l'existant)
  /// @return nouveau record avec les champs modifiés
  public ValeurConcert avecUuids(UUID nouvelleSalleId, UUID nouvelArtisteId) {
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
        nouvelleSalleId,
        nouvelArtisteId);
  }

  /// Retourne un nouveau concert avec l'{@code artisteNorm} et l'{@code artisteId} remplacés.
  ///
  /// @param nouvelArtisteNorm nouveau nom d'artiste normalisé
  /// @param nouvelArtisteId   nouveau UUID d'artiste
  /// @return nouveau record avec les champs modifiés
  public ValeurConcert avecArtisteNormEtId(String nouvelArtisteNorm, UUID nouvelArtisteId) {
    return new ValeurConcert(
        artiste,
        nouvelArtisteNorm,
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
        nouvelArtisteId);
  }
}
