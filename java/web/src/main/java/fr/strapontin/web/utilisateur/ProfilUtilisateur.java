/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

/// Profil de l'utilisateur courant (réponse GET /api/user/profile).
///
/// @since 2.1.0
/// @author Kwaite
public record ProfilUtilisateur(
    String id, String email, String identifiant, String pendingEmail, boolean twoFactorActif) {}
