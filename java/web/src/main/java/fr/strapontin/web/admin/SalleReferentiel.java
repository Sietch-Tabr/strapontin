/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;
import java.util.UUID;

/// Ligne du référentiel salles pour l'onglet admin « Salles » (#710).
///
/// @param id         UUID interne stable
/// @param nom        nom brut (dernière valeur vue par une source)
/// @param nomAffiche nom canonique (enrichissement Wikidata/OSM ou override manuel admin)
/// @param ville      commune connue
/// @param dept       département connu
/// @param nbConcerts nombre de concerts à venir liés à cette salle
/// @param sources    sources externes liées (ex. {@code ["ticketmaster", "digitick"]})
/// @param statut     {@code "a_jour"}, {@code "a_verifier"} ou {@code "invalide"} (basé sur
///     {@code suspect_depuis}, #706, #778)
/// @param wikidataId Q-ID Wikidata si présent
/// @since 2.6.0
/// @author Kwaite
public record SalleReferentiel(
    UUID id,
    String nom,
    String nomAffiche,
    String ville,
    String dept,
    long nbConcerts,
    List<String> sources,
    String statut,
    String wikidataId) {}
