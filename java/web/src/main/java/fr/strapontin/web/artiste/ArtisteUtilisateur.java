/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import java.time.Instant;
import java.util.UUID;

/**
 * Artiste suivi par un utilisateur.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record ArtisteUtilisateur(UUID id, String artisteAffiche, Instant createdAt) {}
