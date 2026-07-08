/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;
import java.util.UUID;

/// Ligne du référentiel artistes pour l'onglet admin « Artistes » (#710).
///
/// @param id         UUID interne stable
/// @param nom        nom brut (dernière valeur vue par une source)
/// @param nomAffiche nom canonique (enrichissement Wikidata/Deezer ou override manuel admin)
/// @param nbConcerts nombre de concerts à venir liés à cet artiste
/// @param sources    sources externes liées (ex. {@code ["ticketmaster", "eventim"]})
/// @param statut     {@code "a_jour"} ou {@code "invalide"} (statut binaire basé sur
///     {@code necessite_intervention_le}, #706, #778, #888)
/// @param wikidataId Q-ID Wikidata si présent
/// @param deezerId   identifiant Deezer si présent
/// @since 2.6.0
/// @author Kwaite
public record ArtisteReferentiel(
    UUID id,
    String nom,
    String nomAffiche,
    long nbConcerts,
    List<String> sources,
    String statut,
    String wikidataId,
    String deezerId) {}
