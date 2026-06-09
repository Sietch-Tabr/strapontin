/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;

/// Représentation admin d'un utilisateur (réponse GET /api/admin/utilisateurs).
///
/// @since 2.1.0
/// @author Kwaite
public record UtilisateurAdmin(
    String id,
    String email,
    String role,
    String username,
    boolean mustChangeCredentials,
    Instant createdAt,
    Instant derniereActivite) {}
