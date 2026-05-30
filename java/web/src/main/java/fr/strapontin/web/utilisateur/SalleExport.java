/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

/// Salle dans un export JSON de préférences.
///
/// @since 2.1.0
/// @author Kwaite
public record SalleExport(String salleId, String tmVenueId, String nom) {}
