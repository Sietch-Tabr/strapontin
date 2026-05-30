/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.salle;

/// Corps JSON de la requête POST /api/salles.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteAjouter(String nom, String tmVenueId) {}
