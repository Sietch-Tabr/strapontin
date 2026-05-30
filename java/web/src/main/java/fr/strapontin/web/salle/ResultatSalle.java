/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

/// Résultat de recherche d'une salle ({@code "base"} ou {@code "ticketmaster"}).
///
/// @since 2.1.0
/// @author Kwaite
public record ResultatSalle(String salle, String ville, String tmVenueId, String source) {}
