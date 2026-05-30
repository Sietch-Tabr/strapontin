/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.artiste;

import java.time.Instant;

/// Artiste suivi par un utilisateur.
///
/// @since 2.1.0
/// @author Kwaite
public record ArtisteUtilisateur(String artisteNormalise, String artisteAffiche, Instant createdAt) {}
