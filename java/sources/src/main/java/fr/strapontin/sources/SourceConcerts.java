/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.util.List;

/**
 * Contrat minimal d'une source de concerts (Ticketmaster, Eventim...)
 * — méthodes non pertinentes pour une source ont une
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
  default List<ConcertBrut> fetchArtiste(final String nom) {
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
   * Recherche avec les autres noms connus (libellé, alias Wikidata) ; par
   * défaut ignorés, délégation à {@link #fetchArtiste(String, String, String)}.
   * @param autresNomsConnus libellé et alias connus de l'artiste
   * @return concerts trouvés, ou liste vide si non supporté
   */
  default List<ConcertBrut> fetchArtiste(
      final String nom,
      final String deezerId,
      final String wikidataId,
      final List<String> autresNomsConnus) {
    return fetchArtiste(nom, deezerId, wikidataId);
  }

  /**
   * Indique si la source accepte cet identifiant de salle préfixé (ex.
   * "tm:rZFSnyZdFvA", "eventim:internal:accor arena").
   * @param identifiant identifiant préfixé de la salle
   * @return {@code true} si la source peut fetcher cette salle
   */
  default boolean accepteIdentifiantSalle(final String identifiant) {
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
  default List<ConcertBrut> fetchSalle(final String identifiant) {
    return List.of();
  }

  /**
   * Comme {@link #fetchSalle(String)}, pour une source qui interroge un lieu
   * par son libellé plutôt que par un identifiant (Eventim).
   * @param identifiant identifiant préfixé de la salle
   * @param libelle libellé du lieu chez la source, ou null
   * @return concerts trouvés
   */
  default List<ConcertBrut> fetchSalle(final String identifiant, final String libelle) {
    return fetchSalle(identifiant);
  }

  /**
   * Recherche les concerts à venir dans une région définie par ses départements.
   *
   * @param depts codes département (ex. {@code ["75", "92"]})
   * @param classifs classifications à inclure
   * @return concerts trouvés, ou liste vide si non supporté
   */
  default List<ConcertBrut> fetchRegion(final List<String> depts, final List<String> classifs) {
    return List.of();
  }

  /**
   * Comme {@link #fetchRegion}, avec l'horizon de couverture garanti — par
   * défaut aucun, ce qui interdit de compter des absences sur cette source.
   * @param depts codes département (ex. {@code ["75", "92"]})
   * @param classifs classifications à inclure
   * @return concerts trouvés et horizon de couverture
   */
  default ResultatRegion fetchRegionCouverte(
      final List<String> depts, final List<String> classifs) {
    return new ResultatRegion(fetchRegion(depts, classifs), null);
  }

  /**
   * Nombre de jours à venir entièrement couverts par {@link #fetchSalle}
   * (fenêtre de la requête) — au-delà, un concert non revu n'est pas absent.
   * @return jours couverts, 0 si la source ne garantit aucune couverture
   */
  default int joursCouvertsParSalle() {
    return 0;
  }
}
