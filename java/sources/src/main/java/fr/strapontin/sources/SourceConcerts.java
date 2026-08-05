/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.util.List;

/// Contrat minimal d'une source de concerts.
///
/// <p>Chaque implémentation gère une billetterie (Ticketmaster, Eventim, Digitick…).
/// Les méthodes non pertinentes pour une source ont une implémentation par défaut
/// qui retourne une liste vide ou {@code false}.
///
/// @since 2.3.1
/// @author Kwaite
public interface SourceConcerts {

  /// Nom court de la source, utilisé dans les logs.
  ///
  /// @return nom de la source (ex. {@code "ticketmaster"})
  String nom();

  /// Recherche les concerts d'un artiste par son nom.
  ///
  /// @param nom nom de l'artiste
  /// @return concerts trouvés, ou liste vide si la source ne supporte pas cette opération
  default List<ConcertBrut> fetchArtiste(String nom) {
    return List.of();
  }

  /// Recherche les concerts d'un artiste par son nom, avec son identité déjà confirmée par une
  /// source indépendante si connue.
  ///
  /// <p>Par défaut, délègue à {@link #fetchArtiste(String)} en ignorant `deezerId`/`wikidataId` —
  /// seule une source dont le matching par nom accepte un repli approximatif (ex. containment
  /// Eventim) a besoin de ce signal pour restreindre ce repli aux artistes dont l'identité n'est
  /// pas encore confirmée.
  ///
  /// @param nom        nom de l'artiste
  /// @param deezerId   identifiant Deezer déjà connu, ou {@code null} si l'identité n'est pas
  ///     confirmée par cette source
  /// @param wikidataId identifiant Wikidata déjà connu, ou {@code null} si l'identité n'est pas
  ///     confirmée par cette source
  /// @return concerts trouvés, ou liste vide si la source ne supporte pas cette opération
  default List<ConcertBrut> fetchArtiste(String nom, String deezerId, String wikidataId) {
    return fetchArtiste(nom);
  }

  /// Indique si la source accepte cet identifiant de salle.
  ///
  /// <p>Les identifiants sont préfixés par la source : {@code "tm:rZFSnyZdFvA"},
  /// {@code "digitick:lempreinte"}, etc.
  ///
  /// @param identifiant identifiant préfixé de la salle
  /// @return {@code true} si la source peut fetcher cette salle
  default boolean accepteIdentifiantSalle(String identifiant) {
    return false;
  }

  /// Recherche les concerts d'une salle par son identifiant préfixé.
  ///
  /// @param identifiant identifiant préfixé de la salle (ex. {@code "tm:rZFSnyZdFvA"})
  /// @return concerts trouvés, ou liste vide si la source ne supporte pas cette opération
  default List<ConcertBrut> fetchSalle(String identifiant) {
    return List.of();
  }

  /// Recherche les concerts à venir dans une région définie par ses départements.
  ///
  /// @param depts    codes département (ex. {@code ["75", "92"]})
  /// @param classifs classifications à inclure
  /// @return concerts trouvés, ou liste vide si la source ne supporte pas cette opération
  default List<ConcertBrut> fetchRegion(List<String> depts, List<String> classifs) {
    return List.of();
  }
}
