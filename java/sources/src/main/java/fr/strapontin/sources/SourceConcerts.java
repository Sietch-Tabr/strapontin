/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.time.LocalDate;
import java.util.List;

/**
 * Contrat minimal d'une source de concerts (Ticketmaster, Eventim,
 * Digitick...) — méthodes non pertinentes pour une source ont une
 * implémentation par défaut (liste vide ou false).
 * @since 2.3.1
 * @author Kwaite
 */
@FunctionalInterface
public interface SourceConcerts {

  /**
   * Nom court de la source, utilisé dans les logs.
   *
   * @return nom de la source (ex. {@code "ticketmaster"})
   */
  String nom();

  /**
   * Recherche les concerts d'un artiste par son nom.
   *
   * @param nom nom de l'artiste
   * @return concerts trouvés, ou liste vide si la source ne supporte pas cette
   *     opération
   */
  default List<ConcertBrut> fetchArtiste(String nom) {
    return List.of();
  }

  /**
   * Recherche par nom, identité déjà confirmée si connue — délègue par
   * défaut à {@link #fetchArtiste(String)} en ignorant les deux identifiants.
   * @param nom nom de l'artiste
   * @param deezerId identifiant Deezer déjà connu, ou null si non confirmé
   * @param wikidataId identifiant Wikidata déjà connu, ou null si non confirmé
   * @return concerts trouvés, ou liste vide si non supporté
   */
  default List<ConcertBrut> fetchArtiste(
      final String nom, final String deezerId, final String wikidataId) {
    return fetchArtiste(nom);
  }

  /**
   * Indique si la source accepte cet identifiant de salle préfixé (ex.
   * "tm:rZFSnyZdFvA", "digitick:lempreinte").
   * @param identifiant identifiant préfixé de la salle
   * @return {@code true} si la source peut fetcher cette salle
   */
  default boolean accepteIdentifiantSalle(String identifiant) {
    return false;
  }

  /**
   * Recherche les concerts d'une salle par son identifiant préfixé.
   *
   * @param identifiant identifiant préfixé de la salle (ex.
   *     {@code "tm:rZFSnyZdFvA"} )
   * @return concerts trouvés, ou liste vide si la source ne supporte pas cette
   *     opération
   */
  default List<ConcertBrut> fetchSalle(String identifiant) {
    return List.of();
  }

  /**
   * Comme {@link #fetchSalle(String)}, pour une source qui interroge un lieu
   * par son libellé plutôt que par un identifiant (Eventim).
   * @param identifiant identifiant préfixé de la salle
   * @param libelle libellé du lieu chez la source, ou null
   * @return concerts trouvés
   */
  default List<ConcertBrut> fetchSalle(String identifiant, String libelle) {
    return fetchSalle(identifiant);
  }

  /**
   * Recherche les concerts à venir dans une région définie par ses départements.
   *
   * @param depts codes département (ex. {@code ["75", "92"]})
   * @param classifs classifications à inclure
   * @return concerts trouvés, ou liste vide si non supporté
   */
  default List<ConcertBrut> fetchRegion(List<String> depts, List<String> classifs) {
    return List.of();
  }

  /**
   * Comme {@link #fetchRegion}, avec l'horizon de couverture garanti — par
   * défaut aucun, ce qui interdit de compter des absences sur cette source.
   * @param depts codes département (ex. {@code ["75", "92"]})
   * @param classifs classifications à inclure
   * @return concerts trouvés et horizon de couverture
   */
  default ResultatRegion fetchRegionCouverte(List<String> depts, List<String> classifs) {
    return new ResultatRegion(fetchRegion(depts, classifs), null);
  }

  /**
   * Dernière date couverte par {@link #fetchSalle} (fenêtre de la requête) —
   * au-delà, un concert non revu n'est pas absent.
   * @return horizon de couverture, ou {@code null} si aucun fetch par salle
   */
  default LocalDate horizonSalle() {
    return null;
  }
}
