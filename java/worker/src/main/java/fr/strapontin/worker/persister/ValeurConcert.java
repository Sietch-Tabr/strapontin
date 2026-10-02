/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.PositionLieu;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Concert prêt pour l'upsert en base : le concert brut de la source, et ses champs résolus au fil
 * de la persistance (noms normalisés, date et heure typées, salle et artiste du référentiel). Les
 * champs bruts restent accessibles directement ({@link #salle()}, {@link #statut()}…).
 *
 * @param brut concert tel que publié par la source
 * @param artisteNorm nom d'artiste normalisé, éventuellement réconcilié
 * @param date date du concert
 * @param heure heure du concert, null si inconnue
 * @param salleNorm nom de salle normalisé, éventuellement réconcilié
 * @param salleId salle du référentiel, null tant que non résolue
 * @param artisteId artiste du référentiel, null tant que non résolu
 * @since 2.0.0
 * @author Kwaite
 */
public record ValeurConcert(
    ConcertBrut brut,
    String artisteNorm,
    LocalDate date,
    LocalTime heure,
    String salleNorm,
    UUID salleId,
    UUID artisteId) {

  /** Valide la présence du concert brut. */
  public ValeurConcert {
    Objects.requireNonNull(brut, "brut");
  }

  /**
   * @param concert concert brut d'une source
   * @return les valeurs initiales du concert, avant toute résolution
   */
  public static ValeurConcert depuis(final ConcertBrut concert) {
    return new ValeurConcert(
        concert,
        Normalisation.normaliserTexte(concert.artiste()),
        LocalDate.parse(concert.date()),
        concert.heure() != null ? LocalTime.parse(concert.heure()) : null,
        Normalisation.normaliserTexte(concert.salle()),
        null,
        null);
  }

  /**
   * @return nom d'artiste publié
   */
  public String artiste() {
    return brut.artiste();
  }

  /**
   * @return nom de salle publié
   */
  public String salle() {
    return brut.salle();
  }

  /**
   * @return ville publiée, peut être null
   */
  public String ville() {
    return brut.ville();
  }

  /**
   * @return département publié, peut être null
   */
  public String dept() {
    return brut.dept();
  }

  /**
   * @return fiche de l'événement, peut être null
   */
  public String url() {
    return brut.url();
  }

  /**
   * @return source du concert
   */
  public String source() {
    return brut.source();
  }

  /**
   * @return classification, peut être null
   */
  public String classification() {
    return brut.classification();
  }

  /**
   * @return genre, peut être null
   */
  public String genre() {
    return brut.genre();
  }

  /**
   * @return statut de vente, peut être null
   */
  public String statut() {
    return brut.statut();
  }

  /**
   * @return début de prévente, peut être null
   */
  public String presaleDebut() {
    return brut.presaleDebut();
  }

  /**
   * @return nom de la prévente, peut être null
   */
  public String presaleNom() {
    return brut.presaleNom();
  }

  /**
   * @return identifiant préfixé du lieu chez la source, peut être null
   */
  public String salleSourceId() {
    return brut.salleSourceId();
  }

  /**
   * @return identifiant préfixé de l'artiste chez la source, peut être null
   */
  public String artisteSourceId() {
    return brut.artisteSourceId();
  }

  /**
   * @return position publiée du lieu
   */
  public PositionLieu position() {
    return brut.position();
  }

  /**
   * Retourne un nouveau concert avec le {@code salleNorm} remplacé.
   *
   * @param nouvelleSalleNorm nouveau nom de salle normalisé
   * @return nouveau record avec le champ modifié
   */
  public ValeurConcert avecSalleNorm(final String nouvelleSalleNorm) {
    return new ValeurConcert(brut, artisteNorm, date, heure, nouvelleSalleNorm, salleId, artisteId);
  }

  /**
   * Retourne un nouveau concert avec le {@code salleId} remplacé.
   *
   * @param nouvelleSalleId nouveau UUID de salle
   * @return nouveau record avec le champ modifié
   */
  public ValeurConcert avecSalleId(final UUID nouvelleSalleId) {
    return new ValeurConcert(brut, artisteNorm, date, heure, salleNorm, nouvelleSalleId, artisteId);
  }

  /**
   * Retourne un nouveau concert avec l'{@code artisteNorm} remplacé.
   *
   * @param nouvelArtisteNorm nouveau nom d'artiste normalisé
   * @return nouveau record avec le champ modifié
   */
  public ValeurConcert avecArtisteNorm(final String nouvelArtisteNorm) {
    return new ValeurConcert(brut, nouvelArtisteNorm, date, heure, salleNorm, salleId, artisteId);
  }

  /**
   * Retourne un nouveau concert avec l'{@code artisteId} remplacé.
   *
   * @param nouvelArtisteId nouveau UUID d'artiste
   * @return nouveau record avec le champ modifié
   */
  public ValeurConcert avecArtisteId(final UUID nouvelArtisteId) {
    return new ValeurConcert(brut, artisteNorm, date, heure, salleNorm, salleId, nouvelArtisteId);
  }

  /**
   * Retourne un nouveau concert avec le {@code salleId} et l'{@code artisteId} remplacés.
   *
   * @param nouvelleSalleId nouveau UUID de salle
   * @param nouvelArtisteId nouveau UUID d'artiste
   * @return nouveau record avec les champs modifiés
   */
  public ValeurConcert avecUuids(final UUID nouvelleSalleId, final UUID nouvelArtisteId) {
    return new ValeurConcert(
        brut, artisteNorm, date, heure, salleNorm, nouvelleSalleId, nouvelArtisteId);
  }

  /**
   * Retourne un nouveau concert avec l'{@code artisteNorm} et l'{@code artisteId} remplacés.
   *
   * @param nouvelArtisteNorm nouveau nom d'artiste normalisé
   * @param nouvelArtisteId nouveau UUID d'artiste
   * @return nouveau record avec les champs modifiés
   */
  public ValeurConcert avecArtisteNormEtId(
      final String nouvelArtisteNorm, final UUID nouvelArtisteId) {
    return new ValeurConcert(
        brut, nouvelArtisteNorm, date, heure, salleNorm, salleId, nouvelArtisteId);
  }
}
