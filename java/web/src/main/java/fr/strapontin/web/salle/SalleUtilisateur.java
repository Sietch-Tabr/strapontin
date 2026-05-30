/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.salle;

import java.time.Instant;

/// Salle suivie par un utilisateur.
///
/// @since 2.1.0
/// @author Kwaite
public record SalleUtilisateur(String salleId, String nom, String tmVenueId, Instant createdAt) {}
