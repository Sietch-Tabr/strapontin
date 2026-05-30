/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

/// Représentation publique d'un utilisateur authentifié.
///
/// @since 2.1.0
/// @author Kwaite
public record UtilisateurDto(
    String id,
    String identifiant,
    String nom,
    String email,
    String role,
    boolean mustChangeCredentials) {}
