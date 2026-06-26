/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

/// Résultat de recherche d'une salle ({@code "base"}, {@code "base-digitick"} ou
/// {@code "ticketmaster"}).
///
/// @param salle        nom de la salle
/// @param ville        ville (null si inconnue)
/// @param tmVenueId    identifiant Ticketmaster (null si non issu de TM)
/// @param digitickSlug slug Digitick pré-configuré (null si non configuré)
/// @param source       origine du résultat : {@code "base"}, {@code "base-digitick"},
///                     {@code "ticketmaster"}
/// @since 2.1.0
/// @author Kwaite
public record ResultatSalle(
    String salle, String ville, String tmVenueId, String digitickSlug, String source) {}
